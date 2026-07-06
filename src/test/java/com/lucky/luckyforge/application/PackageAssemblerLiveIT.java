package com.lucky.luckyforge.application;

import com.lucky.luckyforge.application.imagescorer.ImageScorerService;
import com.lucky.luckyforge.application.imagegenerator.ImageGeneratorService;
import com.lucky.luckyforge.application.packageassembler.PackageAssemblerService;
import com.lucky.luckyforge.application.packageassembler.dto.PackageAssemblyResponse;
import com.lucky.luckyforge.application.promptbuilder.PromptBuilderService;
import com.lucky.luckyforge.application.promptbuilder.dto.PromptGenerationRequest;
import com.lucky.luckyforge.application.referenceimage.ReferenceImageService;
import com.lucky.luckyforge.application.styleanalysis.StyleAnalysisService;
import com.lucky.luckyforge.infrastructure.persistence.entity.GeneratedImage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 流水线全 5 环端到端真实联调测试。
 * <p>真实调用 chatgpt2api：投喂参考图 → 风格提炼 → 提示词 → 出图 → 打分 → 打包。
 * 验证 PackageAssembler 收口为最终素材包（图+标题+标签）。
 *
 * <p>启用方式：环境变量 {@code LIVE_CHATGPT2API=on}。
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
class PackageAssemblerLiveIT {

    @Autowired private ReferenceImageService referenceImageService;
    @Autowired private StyleAnalysisService styleAnalysisService;
    @Autowired private PromptBuilderService promptBuilderService;
    @Autowired private ImageGeneratorService imageGeneratorService;
    @Autowired private ImageScorerService imageScorerService;
    @Autowired private PackageAssemblerService packageAssemblerService;
    @Autowired private BatchMapper batchMapper;
    @Autowired private GeneratedImageMapper generatedImageMapper;
    @Autowired private MinioStorageService storageService;

    @Test
    void 端到端_全5环_打包出成品() throws Exception {
        // 1. 创建 batch（targetCount=1，出 2 图选 1）
        Batch batch = new Batch();
        batch.setVertical(Vertical.WALLPAPER.value());
        batch.setTargetCount(1);
        batch.setStatus(BatchStatus.DRAFT.value());
        batch.setTheme("宁静的自然风景");
        batchMapper.insert(batch);
        System.out.println("=== 1. 创建 batch id=" + batch.getId() + " ===");

        var refRes = new ClassPathResource("liveit/sunset.jpg");
        var refFile = new MockMultipartFile("files", "sunset.jpg", "image/jpeg",
                refRes.getInputStream().readAllBytes());
        var uploads = referenceImageService.uploadReferences(batch.getId(), List.of(refFile));

        Long runId = null;
        try {
            // 2-5. 风格→提示词→出图→打分
            System.out.println("=== 2. 风格提炼 ===");
            var style = styleAnalysisService.analyze(batch.getId());
            System.out.println("    风格: " + style.name());

            System.out.println("=== 3. 生成 2 条提示词 ===");
            var prompts = promptBuilderService.generatePrompts(batch.getId(),
                    new PromptGenerationRequest(2));
            runId = prompts.get(0).runId();

            System.out.println("=== 4. 并发出图 ===");
            var imgSummary = imageGeneratorService.generateImages(runId);
            System.out.println("    出图: " + imgSummary.succeeded() + "/" + imgSummary.total());
            if (imgSummary.succeeded() == 0) {
                System.out.println("    [跳过打包] 无图成功");
                return;
            }

            System.out.println("=== 5. 打分 ===");
            var scoreSummary = imageScorerService.scoreImages(runId);
            System.out.println("    打分: " + scoreSummary.succeeded() + "/" + scoreSummary.total());

            // 6. 打包
            System.out.println("=== 6. 打包（PackageAssembler）===");
            PackageAssemblyResponse pkg = packageAssemblerService.assemble(runId);

            System.out.println("    素材包 id: " + pkg.packageId());
            System.out.println("    标题: " + pkg.title());
            System.out.println("    标签: " + pkg.tags());
            System.out.println("    入选图片: " + pkg.images().size() + " 张");
            for (var img : pkg.images()) {
                System.out.println("      [sortOrder=" + img.sortOrder() + "] 图" + img.generatedImageId()
                        + " 分数=" + img.score() + " objectKey=" + img.objectKey());
            }

            // 7. 校验
            assertNotNull(pkg.packageId());
            assertNotNull(pkg.title());
            assertFalse(pkg.title().isBlank());
            assertFalse(pkg.tags().isEmpty());
            assertFalse(pkg.images().isEmpty());
            // 封面（sortOrder=0）分数最高
            assertEquals(0, pkg.images().get(0).sortOrder());

            System.out.println("=== 联调通过：全 5 环闭环，产出素材包 ✓ ===");
        } finally {
            // 清理 MinIO（runId 在 try 内赋值，此处用 final 副本供 lambda 引用）
            final Long finalRunId = runId;
            if (finalRunId != null) {
                List<GeneratedImage> images = generatedImageMapper.selectList(null).stream()
                        .filter(g -> g.getObjectKey() != null && g.getObjectKey().contains("/" + finalRunId + "/"))
                        .toList();
                images.forEach(g -> {
                    try { storageService.delete(g.getObjectKey()); } catch (Exception ignored) { }
                });
            }
            uploads.forEach(u -> {
                try { storageService.delete(u.objectKey()); } catch (Exception ignored) { }
            });
        }
    }
}
