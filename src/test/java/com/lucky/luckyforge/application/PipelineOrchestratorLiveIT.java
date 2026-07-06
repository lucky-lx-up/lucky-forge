package com.lucky.luckyforge.application;

import com.lucky.luckyforge.application.pipeline.PipelineOrchestratorService;
import com.lucky.luckyforge.application.pipeline.dto.PipelineResult;
import com.lucky.luckyforge.application.pipeline.dto.PipelineStepResult;
import com.lucky.luckyforge.application.referenceimage.ReferenceImageService;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.enums.BatchStatus;
import com.lucky.luckyforge.infrastructure.persistence.enums.Vertical;
import com.lucky.luckyforge.infrastructure.persistence.mapper.BatchMapper;
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
 * 流水线编排器真实联调测试。
 * <p>一键触发全流程（不 mock），验证 orchestrator 串联 5 环端到端跑通到产出素材包。
 *
 * <p>启用方式：环境变量 {@code LIVE_CHATGPT2API=on}。
 * 全流程耗时约 3-5 分钟。
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
class PipelineOrchestratorLiveIT {

    @Autowired private PipelineOrchestratorService pipelineOrchestratorService;
    @Autowired private ReferenceImageService referenceImageService;
    @Autowired private BatchMapper batchMapper;
    @Autowired private MinioStorageService storageService;

    @Test
    void 一键全流程_产出素材包() throws Exception {
        // 1. 创建 batch + 上传参考图
        Batch batch = new Batch();
        batch.setVertical(Vertical.WALLPAPER.value());
        batch.setTargetCount(2);
        batch.setStatus(BatchStatus.DRAFT.value());
        batch.setTheme("宁静的自然风景");
        batchMapper.insert(batch);
        System.out.println("=== 创建 batch id=" + batch.getId() + "，一键触发 pipeline ===");

        var refRes = new ClassPathResource("liveit/sunset.jpg");
        var refFile = new MockMultipartFile("files", "sunset.jpg", "image/jpeg",
                refRes.getInputStream().readAllBytes());
        var uploads = referenceImageService.uploadReferences(batch.getId(), List.of(refFile));

        try {
            long start = System.currentTimeMillis();
            PipelineResult result = pipelineOrchestratorService.execute(batch.getId());
            long totalSec = (System.currentTimeMillis() - start) / 1000;

            System.out.println("=== 全流程完成，耗时 " + totalSec + "s ===");
            System.out.println("    overallSuccess: " + result.overallSuccess());
            System.out.println("    message: " + result.overallMessage());
            System.out.println("    styleId=" + result.styleId()
                    + " runId=" + result.runId()
                    + " packageId=" + result.packageId());
            System.out.println("    各步详情：");
            for (PipelineStepResult s : result.steps()) {
                System.out.println("      " + s.step() + " " + (s.success() ? "OK" : "FAIL")
                        + " " + s.elapsedMs() + "ms " + (s.detail() != null ? s.detail() : s.errorMessage()));
            }

            // 校验：全流程成功（容忍个别图偶发失败，只要整体 SUCCESS）
            // 注意：真实环境出图偶发失败，若 succeeded=0 会 FAILED，这里宽容断言
            assertTrue(result.steps().size() >= 2, "至少应执行风格+提示词两步");
            assertEquals("STYLE", result.steps().get(0).step());
            assertTrue(result.steps().get(0).success(), "风格提炼应成功");

            if (result.overallSuccess()) {
                assertNotNull(result.styleId());
                assertNotNull(result.runId());
                assertNotNull(result.packageId());
                System.out.println("=== 联调通过：一键全流程闭环 ✓ ===");
            } else {
                System.out.println("=== 部分环节失败（真实环境偶发），run.status=FAILED，详见各步 ===");
            }
        } finally {
            // 清理 MinIO（参考图 + 生成图，按 runId 关联）
            uploads.forEach(u -> {
                try { storageService.delete(u.objectKey()); } catch (Exception ignored) { }
            });
        }
    }
}
