package com.lucky.luckyforge.application.styleanalysis;

import com.lucky.luckyforge.application.referenceimage.ReferenceImageService;
import com.lucky.luckyforge.application.referenceimage.dto.ReferenceImageUploadResponse;
import com.lucky.luckyforge.application.styleanalysis.dto.StyleAnalysisResponse;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.infrastructure.chatgpt.ChatGpt2ApiClient;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.entity.Style;
import com.lucky.luckyforge.infrastructure.persistence.enums.BatchStatus;
import com.lucky.luckyforge.infrastructure.persistence.enums.Vertical;
import com.lucky.luckyforge.infrastructure.persistence.mapper.BatchMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.StyleMapper;
import com.lucky.luckyforge.infrastructure.storage.MinioStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link StyleAnalysisService} 集成测试。
 * <p>{@code ChatGpt2ApiClient} 用 Spring Boot 的 {@link MockBean} 替换（mock），
 * MySQL/MinIO 连真实服务。
 *
 * <p>覆盖：正常提炼（lf_style 落库 + batch.styleId 回填）、批次不存在、无参考图、参考图超限、
 * gpt 返回非法 JSON。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StyleAnalysisServiceIT {

    @Autowired private StyleAnalysisService styleAnalysisService;
    @Autowired private ReferenceImageService referenceImageService;
    @Autowired private BatchMapper batchMapper;
    @Autowired private StyleMapper styleMapper;
    @Autowired private MinioStorageService storageService;

    /** mock chatgpt2api，避免依赖真实服务可达性 */
    @MockBean private ChatGpt2ApiClient chatGpt2ApiClient;

    @Test
    void 正常提炼_风格落库且batch回填() {
        // 准备：创建 batch + 上传 1 张参考图
        Batch batch = newBatch();
        batchMapper.insert(batch);
        ReferenceImageUploadResponse uploadResp = uploadRef(batch.getId(), "test.jpg");
        cleanupOnFailure(uploadResp.objectKey(), () -> {

            // mock gpt-5.5 返回固定风格 JSON
            String gptResponse = """
                    {
                      "name": "温暖日落",
                      "description": "以橙红色调为主，构图中心对称，主题为日落山景，氛围宁静温暖",
                      "features": {
                        "palette": "暖色调，以橙红为主",
                        "composition": "中心对称，留白充足",
                        "subject": "日落山景",
                        "mood": "宁静温暖"
                      }
                    }
                    """;
            when(chatGpt2ApiClient.chatCompletion(anyString(), anyList())).thenReturn(gptResponse);

            // 执行
            StyleAnalysisResponse resp = styleAnalysisService.analyze(batch.getId());

            // 断言响应
            assertEquals(batch.getId(), resp.batchId());
            assertEquals("温暖日落", resp.name());
            assertNotNull(resp.styleId());
            assertTrue(resp.description().contains("橙红色调"));

            // 断言 lf_style 落库
            Style style = styleMapper.selectById(resp.styleId());
            assertNotNull(style);
            assertEquals("温暖日落", style.getName());
            assertEquals(Vertical.WALLPAPER.value(), style.getVertical());
            assertNotNull(style.getStyleJson());
            assertTrue(style.getStyleJson().contains("palette"));

            // 断言 batch.styleId 被回填
            Batch updated = batchMapper.selectById(batch.getId());
            assertEquals(resp.styleId(), updated.getStyleId());
        });
    }

    @Test
    void gpt返回带markdown代码块也能解析() {
        Batch batch = newBatch();
        batchMapper.insert(batch);
        ReferenceImageUploadResponse uploadResp = uploadRef(batch.getId(), "md.jpg");
        cleanupOnFailure(uploadResp.objectKey(), () -> {
            // gpt 返回被 ```json ... ``` 包裹
            String gptResponse = """
                    ```json
                    {"name":"极简风景","description":"冷色调极简构图","features":{"palette":"冷色","composition":"极简","subject":"风景","mood":"清冷"}}
                    ```
                    """;
            when(chatGpt2ApiClient.chatCompletion(anyString(), anyList())).thenReturn(gptResponse);

            StyleAnalysisResponse resp = styleAnalysisService.analyze(batch.getId());
            assertEquals("极简风景", resp.name());
        });
    }

    @Test
    void 批次不存在抛BizException() {
        when(chatGpt2ApiClient.chatCompletion(anyString(), anyList())).thenReturn("{}");
        BizException ex = assertThrows(BizException.class,
                () -> styleAnalysisService.analyze(99999999L));
        assertTrue(ex.getMessage().contains("批次不存在"));
    }

    @Test
    void 无参考图抛BizException() {
        Batch batch = newBatch();
        batchMapper.insert(batch);

        BizException ex = assertThrows(BizException.class,
                () -> styleAnalysisService.analyze(batch.getId()));
        assertTrue(ex.getMessage().contains("无参考图"));
        // gpt 不应被调用
        verifyNoInteractions(chatGpt2ApiClient);
    }

    @Test
    void 参考图超限抛BizException() {
        Batch batch = newBatch();
        batchMapper.insert(batch);
        // 上传 6 张（超上限 5）
        for (int i = 0; i < 6; i++) {
            uploadRef(batch.getId(), "over" + i + ".jpg");
        }

        BizException ex = assertThrows(BizException.class,
                () -> styleAnalysisService.analyze(batch.getId()));
        assertTrue(ex.getMessage().contains("超限"));
        verifyNoInteractions(chatGpt2ApiClient);
    }

    @Test
    void gpt返回非法JSON抛BizException() {
        Batch batch = newBatch();
        batchMapper.insert(batch);
        ReferenceImageUploadResponse uploadResp = uploadRef(batch.getId(), "bad.jpg");
        cleanupOnFailure(uploadResp.objectKey(), () -> {
            when(chatGpt2ApiClient.chatCompletion(anyString(), anyList())).thenReturn("这不是JSON");

            BizException ex = assertThrows(BizException.class,
                    () -> styleAnalysisService.analyze(batch.getId()));
            assertTrue(ex.getMessage().contains("解析风格 JSON 失败"));
        });
    }

    // ===== 辅助方法 =====

    private Batch newBatch() {
        Batch b = new Batch();
        b.setVertical(Vertical.WALLPAPER.value());
        b.setTargetCount(1);
        b.setStatus(BatchStatus.DRAFT.value());
        return b;
    }

    private ReferenceImageUploadResponse uploadRef(Long batchId, String filename) {
        var file = new MockMultipartFile(
                "files", filename, "image/jpeg", ("img-" + filename).getBytes());
        List<ReferenceImageUploadResponse> result =
                referenceImageService.uploadReferences(batchId, List.of(file));
        return result.get(0);
    }

    /**
     * 测试体内上传的 MinIO 对象无法被 @Transactional 回滚，故用本包装保证异常/成功都清理。
     *
     * @param objectKey 待清理的 MinIO 对象
     * @param action    测试体
     */
    private void cleanupOnFailure(String objectKey, Runnable action) {
        try {
            action.run();
        } finally {
            try {
                storageService.delete(objectKey);
            } catch (Exception ignored) {
                // 清理失败不掩盖测试结果
            }
        }
    }
}
