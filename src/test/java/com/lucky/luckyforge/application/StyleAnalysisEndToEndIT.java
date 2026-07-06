package com.lucky.luckyforge.application;

import com.lucky.luckyforge.application.referenceimage.ReferenceImageService;
import com.lucky.luckyforge.application.referenceimage.dto.ReferenceImageUploadResponse;
import com.lucky.luckyforge.application.styleanalysis.StyleAnalysisService;
import com.lucky.luckyforge.application.styleanalysis.dto.StyleAnalysisResponse;
import com.lucky.luckyforge.infrastructure.chatgpt.ChatGpt2ApiClient;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.entity.ReferenceImage;
import com.lucky.luckyforge.infrastructure.persistence.enums.BatchStatus;
import com.lucky.luckyforge.infrastructure.persistence.enums.Vertical;
import com.lucky.luckyforge.infrastructure.persistence.mapper.BatchMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.ReferenceImageMapper;
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
 * 端到端冒烟测试：参考图上传 → 风格提炼 → 全链路校验。
 * <p>校验整条流水线第一环的端到端行为：
 * MinIO 有图、lf_reference_image 有记录、lf_style 有记录、batch.styleId 已回填。
 *
 * <p>{@code @MockBean ChatGpt2ApiClient} 避免依赖真实 chatgpt2api 服务可达性。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StyleAnalysisEndToEndIT {

    @Autowired private ReferenceImageService referenceImageService;
    @Autowired private StyleAnalysisService styleAnalysisService;
    @Autowired private BatchMapper batchMapper;
    @Autowired private ReferenceImageMapper referenceImageMapper;
    @Autowired private StyleMapper styleMapper;
    @Autowired private MinioStorageService storageService;

    @MockBean private ChatGpt2ApiClient chatGpt2ApiClient;

    @Test
    void 端到端_上传参考图触发分析_全链路落库() {
        // 1. 创建 batch
        Batch batch = new Batch();
        batch.setVertical(Vertical.WALLPAPER.value());
        batch.setTargetCount(8);
        batch.setStatus(BatchStatus.DRAFT.value());
        batchMapper.insert(batch);
        Long batchId = batch.getId();
        assertNull(batch.getStyleId(), "初始 batch.styleId 应为空");

        // 2. 上传 2 张参考图
        var f1 = new MockMultipartFile("files", "e2e-1.jpg", "image/jpeg", "img1".getBytes());
        var f2 = new MockMultipartFile("files", "e2e-2.jpg", "image/jpeg", "img2".getBytes());
        List<ReferenceImageUploadResponse> uploads =
                referenceImageService.uploadReferences(batchId, List.of(f1, f2));
        assertEquals(2, uploads.size());

        // 校验：MinIO 有图、lf_reference_image 有记录
        List<ReferenceImage> refs = referenceImageMapper.selectList(null);
        long refCount = refs.stream().filter(r -> batchId.equals(r.getBatchId())).count();
        assertEquals(2, refCount, "lf_reference_image 应有 2 条记录");
        uploads.forEach(u -> {
            byte[] downloaded = storageService.download(u.objectKey());
            assertNotNull(downloaded);
            assertTrue(downloaded.length > 0);
        });

        // 3. mock gpt 响应并触发风格提炼
        String gptJson = """
                {"name":"端到端测试风格","description":"综合风格描述","features":{"palette":"综合色调","composition":"综合构图","subject":"综合主题","mood":"综合氛围"}}
                """;
        when(chatGpt2ApiClient.chatCompletion(anyString(), anyList())).thenReturn(gptJson);

        StyleAnalysisResponse resp = styleAnalysisService.analyze(batchId);

        // 4. 校验全链路落库
        assertNotNull(resp.styleId());
        assertEquals("端到端测试风格", resp.name());
        assertEquals(batchId, resp.batchId());

        // lf_style 有记录
        assertNotNull(styleMapper.selectById(resp.styleId()));

        // batch.styleId 已回填
        Batch updated = batchMapper.selectById(batchId);
        assertEquals(resp.styleId(), updated.getStyleId(), "batch.styleId 应已回填");

        // 5. 清理 MinIO（DB 由 @Transactional 回滚）
        uploads.forEach(u -> {
            try {
                storageService.delete(u.objectKey());
            } catch (Exception ignored) {
            }
        });
    }
}
