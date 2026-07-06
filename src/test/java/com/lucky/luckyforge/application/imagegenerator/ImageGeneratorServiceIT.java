package com.lucky.luckyforge.application.imagegenerator;

import com.lucky.luckyforge.application.imagegenerator.dto.ImageGenerationResult;
import com.lucky.luckyforge.application.imagegenerator.dto.ImageGenerationSummary;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.infrastructure.chatgpt.ChatGpt2ApiClient;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.entity.GeneratedImage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Prompt;
import com.lucky.luckyforge.infrastructure.persistence.entity.Run;
import com.lucky.luckyforge.infrastructure.persistence.enums.BatchStatus;
import com.lucky.luckyforge.infrastructure.persistence.enums.RunStatus;
import com.lucky.luckyforge.infrastructure.persistence.enums.Vertical;
import com.lucky.luckyforge.infrastructure.persistence.mapper.*;
import com.lucky.luckyforge.infrastructure.storage.MinioStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ImageGeneratorService} 集成测试（mock chatgpt2api，连真实 MySQL + MinIO）。
 * <p>验证：并发出图入 MinIO、lf_generated_image 落库、run 状态流转、部分失败容错。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ImageGeneratorServiceIT {

    @Autowired private ImageGeneratorService imageGeneratorService;
    @Autowired private BatchMapper batchMapper;
    @Autowired private RunMapper runMapper;
    @Autowired private PromptMapper promptMapper;
    @Autowired private GeneratedImageMapper generatedImageMapper;
    @Autowired private MinioStorageService storageService;

    @MockBean private ChatGpt2ApiClient chatGpt2ApiClient;

    /** 1x1 红点 PNG 的 Base64（出图 mock 返回值） */
    private static final String RED_DOT_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==";

    @Test
    void 正常并发出图_全部成功() {
        Long[] ids = setupRunWithPrompts(3);
        Long batchId = ids[0], runId = ids[1];
        try {
            // mock 所有 prompt 都返回红点
            when(chatGpt2ApiClient.generateImage(anyString(), eq("1024x1792")))
                    .thenReturn(RED_DOT_B64);

            ImageGenerationSummary summary = imageGeneratorService.generateImages(runId);

            assertEquals(3, summary.total());
            assertEquals(3, summary.succeeded());
            assertEquals(0, summary.failed());

            // 每条结果都成功，对应 lf_generated_image 落库
            for (var r : summary.results()) {
                assertTrue(r.success());
                assertNotNull(r.generatedImageId());
                // MinIO 有图且可下载
                byte[] downloaded = storageService.download(r.objectKey());
                assertTrue(downloaded.length > 0);
            }

            // run 状态 SUCCESS
            Run run = runMapper.selectById(runId);
            assertEquals(RunStatus.SUCCESS.value(), run.getStatus());
            assertEquals("GENERATE", run.getCurrentStep());
            assertNotNull(run.getFinishedAt());
        } finally {
            cleanupMinIO(runId);
        }
    }

    @Test
    void 部分失败_成功的图保留() {
        Long[] ids = setupRunWithPrompts(3);
        Long batchId = ids[0], runId = ids[1];
        try {
            // 按 prompt 内容 mock：含 "scene 2" 的抛异常（并发安全，按入参而非顺序匹配）
            when(chatGpt2ApiClient.generateImage(
                        argThat((String s) -> s != null && s.contains("scene 2")), anyString()))
                    .thenThrow(new com.lucky.luckyforge.common.exception.ChatGptApiException(
                            "出图失败", 500, "err", null));
            when(chatGpt2ApiClient.generateImage(
                        argThat((String s) -> s == null || !s.contains("scene 2")), anyString()))
                    .thenReturn(RED_DOT_B64);

            ImageGenerationSummary summary = imageGeneratorService.generateImages(runId);

            assertEquals(3, summary.total());
            assertEquals(2, summary.succeeded());
            assertEquals(1, summary.failed());

            // run 状态 FAILED，error 含失败明细
            Run run = runMapper.selectById(runId);
            assertEquals(RunStatus.FAILED.value(), run.getStatus());
            assertNotNull(run.getError());
            // 成功的 2 张图对应的结果有 generatedImageId
            long successWithId = summary.results().stream()
                    .filter(ImageGenerationResult::success)
                    .filter(r -> r.generatedImageId() != null)
                    .count();
            assertEquals(2, successWithId);
        } finally {
            cleanupMinIO(runId);
        }
    }

    @Test
    void run无提示词时拒绝() {
        Batch batch = newBatch();
        batchMapper.insert(batch);
        Run run = newRun(batch.getId());
        runMapper.insert(run);

        BizException ex = assertThrows(BizException.class,
                () -> imageGeneratorService.generateImages(run.getId()));
        assertTrue(ex.getMessage().contains("无提示词"));
    }

    @Test
    void run不存在时拒绝() {
        BizException ex = assertThrows(BizException.class,
                () -> imageGeneratorService.generateImages(99999999L));
        assertTrue(ex.getMessage().contains("运行不存在"));
    }

    // ===== 辅助方法 =====

    /** 创建 batch + run + N 条 prompt，返回 [batchId, runId] */
    private Long[] setupRunWithPrompts(int promptCount) {
        Batch batch = newBatch();
        batchMapper.insert(batch);
        Run run = newRun(batch.getId());
        runMapper.insert(run);
        for (int i = 1; i <= promptCount; i++) {
            Prompt p = new Prompt();
            p.setRunId(run.getId());
            p.setSeq(i);
            p.setContent("minimalist scene " + i);
            promptMapper.insert(p);
        }
        return new Long[]{batch.getId(), run.getId()};
    }

    private Batch newBatch() {
        Batch b = new Batch();
        b.setVertical(Vertical.WALLPAPER.value());
        b.setTargetCount(promptCount());
        b.setStatus(BatchStatus.DRAFT.value());
        b.setTheme("test");
        return b;
    }

    /** 占位，实际用不到 */
    private int promptCount() { return 3; }

    private Run newRun(Long batchId) {
        Run r = new Run();
        r.setBatchId(batchId);
        r.setStatus(RunStatus.RUNNING.value());
        r.setCurrentStep("PROMPT");
        return r;
    }

    /** 清理 run 下所有生成图对应的 MinIO 对象 */
    private void cleanupMinIO(Long runId) {
        List<GeneratedImage> images = generatedImageMapper.selectList(null);
        for (GeneratedImage gi : images) {
            if (gi.getObjectKey() != null && gi.getObjectKey().contains("/" + runId + "/")) {
                try { storageService.delete(gi.getObjectKey()); } catch (Exception ignored) { }
            }
        }
    }
}
