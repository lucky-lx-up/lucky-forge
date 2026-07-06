package com.lucky.luckyforge.application;

import com.lucky.luckyforge.application.imagegenerator.ImageGeneratorService;
import com.lucky.luckyforge.application.imagegenerator.dto.ImageGenerationSummary;
import com.lucky.luckyforge.application.promptbuilder.PromptBuilderService;
import com.lucky.luckyforge.application.promptbuilder.dto.PromptGenerationRequest;
import com.lucky.luckyforge.application.promptbuilder.dto.PromptGenerationResponse;
import com.lucky.luckyforge.application.referenceimage.ReferenceImageService;
import com.lucky.luckyforge.application.styleanalysis.StyleAnalysisService;
import com.lucky.luckyforge.application.styleanalysis.dto.StyleAnalysisResponse;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 流水线第①②③环端到端真实联调测试。
 * <p>真实调用 chatgpt2api（不 mock）：投喂参考图 → StyleAnalyzer 提炼风格
 * → PromptBuilder 生成提示词 → ImageGenerator 虚拟线程并发出图入 MinIO。
 *
 * <p>启用方式：环境变量 {@code LIVE_CHATGPT2API=on}。
 * 真实地址与 key 通过 {@link TestPropertySource} 注入，不入库默认配置。
 *
 * <p>本测试是项目核心产出能力的端到端验证——首次真实出图。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "chatgpt2api.base-url=http://192.168.2.137:13000",
        "chatgpt2api.api-key=00000000",
        "chatgpt2api.chat-timeout-seconds=90",
        "chatgpt2api.image-timeout-seconds=120",
        // 联调用低并发（2），稳妥观察 chatgpt2api 反应
        "image.concurrent-limit=2"
})
@EnabledIfEnvironmentVariable(named = "LIVE_CHATGPT2API", matches = "on")
class PromptAndImageLiveIT {

    @Autowired private ReferenceImageService referenceImageService;
    @Autowired private StyleAnalysisService styleAnalysisService;
    @Autowired private PromptBuilderService promptBuilderService;
    @Autowired private ImageGeneratorService imageGeneratorService;
    @Autowired private BatchMapper batchMapper;
    @Autowired private GeneratedImageMapper generatedImageMapper;
    @Autowired private MinioStorageService storageService;

    @Test
    @Transactional
    void 端到端_风格提炼_提示词生成_并发出图() throws Exception {
        // ===== 1. 创建 batch + 投喂参考图 =====
        Batch batch = new Batch();
        batch.setVertical(Vertical.WALLPAPER.value());
        batch.setTargetCount(2);
        batch.setStatus(BatchStatus.DRAFT.value());
        batch.setTheme("宁静的自然风景");
        batchMapper.insert(batch);
        System.out.println("=== 1. 创建 batch id=" + batch.getId() + " ===");

        var sunsetRes = new ClassPathResource("liveit/sunset.jpg");
        var sunsetFile = new MockMultipartFile(
                "files", "sunset.jpg", "image/jpeg", sunsetRes.getInputStream().readAllBytes());
        var uploads = referenceImageService.uploadReferences(batch.getId(), List.of(sunsetFile));
        System.out.println("    上传参考图: " + uploads.size() + " 张");

        try {
            // ===== 2. StyleAnalyzer 提炼风格 =====
            System.out.println("=== 2. 触发风格提炼（真实 gpt-5.5 多模态）===");
            StyleAnalysisResponse style = styleAnalysisService.analyze(batch.getId());
            System.out.println("    风格名称: " + style.name());
            System.out.println("    风格描述: " + style.description());

            // ===== 3. PromptBuilder 生成提示词 =====
            System.out.println("=== 3. 触发提示词生成（真实 gpt-5.5，3 条）===");
            List<PromptGenerationResponse> prompts = promptBuilderService.generatePrompts(
                    batch.getId(), new PromptGenerationRequest(2));
            System.out.println("    生成提示词: " + prompts.size() + " 条");
            for (PromptGenerationResponse p : prompts) {
                System.out.println("    [seq=" + p.seq() + "] " + p.content());
            }
            Long runId = prompts.get(0).runId();
            assertTrue(prompts.size() >= 2, "至少应生成 2 条提示词");
            // 提示词是英文
            assertTrue(prompts.get(0).content().matches("^[\\x00-\\x7F]+$"),
                    "提示词应为英文");

            // ===== 4. ImageGenerator 虚拟线程并发出图 =====
            System.out.println("=== 4. 触发并发出图（真实 gpt-image-2，并发=2）===");
            long startMs = System.currentTimeMillis();
            ImageGenerationSummary summary = imageGeneratorService.generateImages(runId);
            long elapsedSec = (System.currentTimeMillis() - startMs) / 1000;
            System.out.println("    出图完成: 耗时 " + elapsedSec + "s");
            System.out.println("    汇总: total=" + summary.total()
                    + " succeeded=" + summary.succeeded()
                    + " failed=" + summary.failed());
            for (var r : summary.results()) {
                if (r.success()) {
                    System.out.println("    [seq=" + r.seq() + "] OK -> " + r.objectKey());
                } else {
                    System.out.println("    [seq=" + r.seq() + "] FAIL -> " + r.errorMessage());
                }
            }

            // ===== 5. 校验全链路落库 =====
            System.out.println("=== 5. 校验全链路 ===");
            assertTrue(summary.succeeded() >= 1, "至少 1 张图成功");
            // 每张成功的图 MinIO 可下载
            for (var r : summary.results()) {
                if (r.success()) {
                    byte[] img = storageService.download(r.objectKey());
                    System.out.println("    下载 " + r.objectKey() + ": " + img.length + " bytes");
                    assertTrue(img.length > 1000, "图片字节数应 > 1000（真实图片）");
                }
            }
            System.out.println("=== 联调通过：风格→提示词→出图 全链路 ✓ ===");
        } finally {
            // 清理 MinIO（DB 由 @Transactional 回滚）
            List<GeneratedImage> images = generatedImageMapper.selectList(null);
            for (GeneratedImage gi : images) {
                if (gi.getObjectKey() != null && gi.getObjectKey().contains("/raw/")) {
                    try { storageService.delete(gi.getObjectKey()); } catch (Exception ignored) { }
                }
            }
            uploads.forEach(u -> {
                try { storageService.delete(u.objectKey()); } catch (Exception ignored) { }
            });
        }
    }
}
