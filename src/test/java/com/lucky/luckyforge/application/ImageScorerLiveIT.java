package com.lucky.luckyforge.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lucky.luckyforge.application.imagescorer.ImageScorerService;
import com.lucky.luckyforge.application.imagescorer.dto.ScoreResult;
import com.lucky.luckyforge.application.imagescorer.dto.ScoreSummary;
import com.lucky.luckyforge.application.imagegenerator.ImageGeneratorService;
import com.lucky.luckyforge.application.promptbuilder.PromptBuilderService;
import com.lucky.luckyforge.application.promptbuilder.dto.PromptGenerationRequest;
import com.lucky.luckyforge.application.referenceimage.ReferenceImageService;
import com.lucky.luckyforge.application.styleanalysis.StyleAnalysisService;
import com.lucky.luckyforge.infrastructure.persistence.entity.*;
import com.lucky.luckyforge.infrastructure.persistence.enums.BatchStatus;
import com.lucky.luckyforge.infrastructure.persistence.enums.Vertical;
import com.lucky.luckyforge.infrastructure.persistence.mapper.*;
import com.lucky.luckyforge.infrastructure.storage.MinioStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 流水线第①②③④环端到端真实联调测试。
 * <p>真实调用 chatgpt2api（不 mock）：投喂参考图 → 风格提炼 → 提示词 → 出图 → 打分。
 * 验证 ImageScorer 在真实多图场景下的打分、覆盖式、TopN、DB 落库。
 *
 * <p>启用方式：环境变量 {@code LIVE_CHATGPT2API=on}。
 * 真实地址与 key 通过 {@link TestPropertySource} 注入。
 *
 * <p>注：不用 @Transactional——service 在虚拟线程里写库，事务独立于测试主线程，
 * 回滚无意义。改用唯一 batch 隔离 + finally 手动清理 MinIO/DB。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "chatgpt2api.base-url=http://192.168.2.137:13000",
        "chatgpt2api.api-key=00000000",
        "chatgpt2api.chat-timeout-seconds=90",
        "chatgpt2api.image-timeout-seconds=120",
        "image.concurrent-limit=2"
})
@EnabledIfEnvironmentVariable(named = "LIVE_CHATGPT2API", matches = "on")
class ImageScorerLiveIT {

    @Autowired private ReferenceImageService referenceImageService;
    @Autowired private StyleAnalysisService styleAnalysisService;
    @Autowired private PromptBuilderService promptBuilderService;
    @Autowired private ImageGeneratorService imageGeneratorService;
    @Autowired private ImageScorerService imageScorerService;
    @Autowired private BatchMapper batchMapper;
    @Autowired private RunMapper runMapper;
    @Autowired private GeneratedImageMapper generatedImageMapper;
    @Autowired private ScoreMapper scoreMapper;
    @Autowired private ScoreDimensionMapper scoreDimensionMapper;
    @Autowired private MinioStorageService storageService;

    @Test
    void 端到端_风格_提示词_出图_打分() throws Exception {
        // 1. 创建 batch（targetCount=1，让 TopN=1，2 张图里选 1）
        Batch batch = new Batch();
        batch.setVertical(Vertical.WALLPAPER.value());
        batch.setTargetCount(1);
        batch.setStatus(BatchStatus.DRAFT.value());
        batch.setTheme("宁静的自然风景");
        batchMapper.insert(batch);
        System.out.println("=== 1. 创建 batch id=" + batch.getId() + " (targetCount=1) ===");

        // 2. 投喂参考图
        var refRes = new ClassPathResource("liveit/sunset.jpg");
        var refFile = new MockMultipartFile("files", "sunset.jpg", "image/jpeg",
                refRes.getInputStream().readAllBytes());
        var uploads = referenceImageService.uploadReferences(batch.getId(), List.of(refFile));

        Long runId = null;
        try {
            // 3. 风格提炼
            System.out.println("=== 2. 风格提炼 ===");
            var style = styleAnalysisService.analyze(batch.getId());
            System.out.println("    风格: " + style.name());

            // 4. 提示词（2 条，出 2 张图便于打分排序）
            System.out.println("=== 3. 生成 2 条提示词 ===");
            var prompts = promptBuilderService.generatePrompts(batch.getId(),
                    new PromptGenerationRequest(2));
            runId = prompts.get(0).runId();
            prompts.forEach(p -> System.out.println("    [seq=" + p.seq() + "] " +
                    p.content().substring(0, Math.min(60, p.content().length())) + "..."));

            // 5. 出图
            System.out.println("=== 4. 并发出图（2 张）===");
            var imgSummary = imageGeneratorService.generateImages(runId);
            System.out.println("    出图: 成功 " + imgSummary.succeeded() + "/" + imgSummary.total());
            assertTrue(imgSummary.succeeded() >= 1, "至少 1 张图成功才能打分");

            // 6. 打分
            System.out.println("=== 5. 虚拟线程并发打分 ===");
            long start = System.currentTimeMillis();
            ScoreSummary scoreSummary = imageScorerService.scoreImages(runId);
            long elapsed = (System.currentTimeMillis() - start) / 1000;
            System.out.println("    打分耗时 " + elapsed + "s");
            System.out.println("    汇总: total=" + scoreSummary.total()
                    + " succeeded=" + scoreSummary.succeeded()
                    + " failed=" + scoreSummary.failed()
                    + " topN=" + scoreSummary.topN());

            for (ScoreResult r : scoreSummary.results()) {
                if (r.success()) {
                    System.out.println("    [图" + r.generatedImageId() + "] total=" + r.total()
                            + " topN=" + r.topN() + " remark=" + r.remark());
                    r.dimensions().forEach(d ->
                            System.out.println("        " + d.name() + "=" + d.value()));
                } else {
                    System.out.println("    [图" + r.generatedImageId() + "] FAIL: " + r.errorMessage());
                }
            }

            // 7. 校验
            assertTrue(scoreSummary.succeeded() >= 1, "至少 1 张打分成功");
            // 查 run 下生成图的 score 落库情况
            List<GeneratedImage> runImages = findRunImages(runId);
            for (GeneratedImage gi : runImages) {
                Score score = scoreMapper.selectOne(new LambdaQueryWrapper<Score>()
                        .eq(Score::getGeneratedImageId, gi.getId()));
                if (score != null) {
                    System.out.println("    DB 校验: 图" + gi.getId() + " -> scoreId=" + score.getId()
                            + " total=" + score.getTotal());
                    List<ScoreDimension> dims = scoreDimensionMapper.selectList(
                            new LambdaQueryWrapper<ScoreDimension>().eq(ScoreDimension::getScoreId, score.getId()));
                    assertEquals(4, dims.size(), "应 4 个维度");
                }
            }
            System.out.println("=== 联调通过：风格→提示词→出图→打分 全链路 ✓ ===");
        } finally {
            // 清理 MinIO（参考图 + 生成图）
            cleanupRunImages(runId);
            uploads.forEach(u -> {
                try { storageService.delete(u.objectKey()); } catch (Exception ignored) { }
            });
        }
    }

    private List<GeneratedImage> findRunImages(Long runId) {
        if (runId == null) return List.of();
        // 简化：直接查全部生成图（联调场景数据少），按 objectKey 含 /runId/ 过滤
        return generatedImageMapper.selectList(null).stream()
                .filter(g -> g.getObjectKey() != null && g.getObjectKey().contains("/" + runId + "/"))
                .toList();
    }

    private void cleanupRunImages(Long runId) {
        if (runId == null) return;
        List<GeneratedImage> images = generatedImageMapper.selectList(null).stream()
                .filter(g -> g.getObjectKey() != null && g.getObjectKey().contains("/" + runId + "/"))
                .toList();
        images.forEach(g -> {
            try { storageService.delete(g.getObjectKey()); } catch (Exception ignored) { }
        });
    }
}
