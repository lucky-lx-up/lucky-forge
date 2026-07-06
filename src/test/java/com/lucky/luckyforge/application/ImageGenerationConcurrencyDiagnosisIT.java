package com.lucky.luckyforge.application;

import com.lucky.luckyforge.application.imagegenerator.ImageGeneratorService;
import com.lucky.luckyforge.application.imagegenerator.dto.ImageGenerationSummary;
import com.lucky.luckyforge.application.promptbuilder.PromptBuilderService;
import com.lucky.luckyforge.application.promptbuilder.dto.PromptGenerationRequest;
import com.lucky.luckyforge.application.promptbuilder.dto.PromptGenerationResponse;
import com.lucky.luckyforge.application.referenceimage.ReferenceImageService;
import com.lucky.luckyforge.application.styleanalysis.StyleAnalysisService;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.entity.GeneratedImage;
import com.lucky.luckyforge.infrastructure.persistence.enums.BatchStatus;
import com.lucky.luckyforge.infrastructure.persistence.enums.Vertical;
import com.lucky.luckyforge.infrastructure.persistence.mapper.BatchMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.GeneratedImageMapper;
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

/**
 * 出图并发对照诊断测试。
 * <p>验证 octet-stream 偶发失败是否由并发触发：
 * 用指定并发度出 4 张图，统计成功率。
 *
 * <p>启用：LIVE_CHATGPT2API=on IMAGE_CONCURRENT_LIMIT=2（或 1）
 * 分别跑两次对比。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "chatgpt2api.base-url=http://192.168.2.137:13000",
        "chatgpt2api.api-key=00000000",
        "chatgpt2api.chat-timeout-seconds=90",
        "chatgpt2api.image-timeout-seconds=120"
})
@EnabledIfEnvironmentVariable(named = "LIVE_CHATGPT2API", matches = "on")
class ImageGenerationConcurrencyDiagnosisIT {

    @Autowired private ReferenceImageService referenceImageService;
    @Autowired private StyleAnalysisService styleAnalysisService;
    @Autowired private PromptBuilderService promptBuilderService;
    @Autowired private ImageGeneratorService imageGeneratorService;
    @Autowired private BatchMapper batchMapper;
    @Autowired private GeneratedImageMapper generatedImageMapper;
    @Autowired private MinioStorageService storageService;

    @Test
    void 出图成功率统计_4张() throws Exception {
        int concurrentLimit = Integer.parseInt(System.getenv().getOrDefault("IMAGE_CONCURRENT_LIMIT", "2"));
        System.out.println("\n========== 出图诊断（并发=" + concurrentLimit + "，出 4 张）==========");

        // 1. 创建 batch + 风格 + 4 条提示词
        Batch batch = new Batch();
        batch.setVertical(Vertical.WALLPAPER.value());
        batch.setTargetCount(4);
        batch.setStatus(BatchStatus.DRAFT.value());
        batch.setTheme("宁静的自然风景");
        batchMapper.insert(batch);

        var refRes = new ClassPathResource("liveit/sunset.jpg");
        var refFile = new MockMultipartFile("files", "sunset.jpg", "image/jpeg",
                refRes.getInputStream().readAllBytes());
        var uploads = referenceImageService.uploadReferences(batch.getId(), List.of(refFile));

        Long runId = null;
        try {
            styleAnalysisService.analyze(batch.getId());
            List<PromptGenerationResponse> prompts = promptBuilderService.generatePrompts(
                    batch.getId(), new PromptGenerationRequest(4));
            runId = prompts.get(0).runId();
            System.out.println("提示词数: " + prompts.size());

            // 2. 出图
            long start = System.currentTimeMillis();
            ImageGenerationSummary summary = imageGeneratorService.generateImages(runId);
            long elapsed = (System.currentTimeMillis() - start) / 1000;

            System.out.println("--- 结果 ---");
            System.out.println("总耗时: " + elapsed + "s");
            System.out.println("成功: " + summary.succeeded() + "/" + summary.total());
            System.out.println("失败: " + summary.failed());
            summary.results().forEach(r -> {
                if (r.success()) {
                    System.out.println("  [seq=" + r.seq() + "] OK " + r.objectKey());
                } else {
                    System.out.println("  [seq=" + r.seq() + "] FAIL: " + r.errorMessage());
                }
            });
            System.out.println("========== 诊断结束 ==========\n");

            // 清理 MinIO
            final Long finalRunId = runId;
            List<GeneratedImage> images = generatedImageMapper.selectList(null).stream()
                    .filter(g -> g.getObjectKey() != null && g.getObjectKey().contains("/" + finalRunId + "/"))
                    .toList();
            images.forEach(g -> { try { storageService.delete(g.getObjectKey()); } catch (Exception ignored) {} });
        } finally {
            uploads.forEach(u -> { try { storageService.delete(u.objectKey()); } catch (Exception ignored) {} });
        }
    }
}
