package com.lucky.luckyforge.application.referenceimage;

import com.lucky.luckyforge.application.batch.BatchService;
import com.lucky.luckyforge.application.batch.dto.BatchCreateRequest;
import com.lucky.luckyforge.application.referenceimage.dto.ReferenceImageUploadResponse;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.infrastructure.persistence.entity.ReferenceImage;
import com.lucky.luckyforge.infrastructure.persistence.mapper.ReferenceImageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReferenceImageService} 集成测试（连真实 MySQL / MinIO）。
 * <p>用 @Transactional 自动回滚 DB；MinIO 上传在事务外，但测试用例刻意选用小字节，且删除走 best-effort，不会因 MinIO 环境差异导致断言失败。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReferenceImageServiceIT {

    @Autowired private ReferenceImageService referenceImageService;
    @Autowired private BatchService batchService;
    @Autowired private ReferenceImageMapper referenceImageMapper;

    @Test
    void 上传参考图_全部失败抛异常() {
        Long batchId = batchService.createBatch(new BatchCreateRequest("test", 1, null));
        // 单张非图片 → uploadOne 抛 BizException → results 为空 → 整批失败抛异常
        MockMultipartFile txt = new MockMultipartFile(
                "files", "note.txt", "text/plain", "hello".getBytes());

        BizException ex = assertThrows(BizException.class,
                () -> referenceImageService.uploadReferences(batchId, List.of(txt)));
        assertTrue(ex.getMessage().contains("全部"), "全失败时应抛整批失败异常");
    }

    @Test
    void 上传参考图_部分失败返回成功列表() {
        Long batchId = batchService.createBatch(new BatchCreateRequest("test", 1, null));
        MockMultipartFile img = new MockMultipartFile(
                "files", "ref.jpg", "image/jpeg", new byte[]{1, 2, 3});
        MockMultipartFile txt = new MockMultipartFile(
                "files", "note.txt", "text/plain", "hello".getBytes());

        // 混入一张非图片：成功的图应保留，失败的跳过，不抛异常
        List<ReferenceImageUploadResponse> result =
                referenceImageService.uploadReferences(batchId, List.of(img, txt));

        assertEquals(1, result.size(), "仅 1 张图片成功");
        assertNotNull(result.get(0).previewUrl());
        assertTrue(result.get(0).previewUrl().startsWith("http"));
    }

    @Test
    void 上传参考图_成功返回预览URL() {
        Long batchId = batchService.createBatch(new BatchCreateRequest("test", 1, null));
        MockMultipartFile img = new MockMultipartFile(
                "files", "ref.jpg", "image/jpeg", new byte[]{1, 2, 3});

        List<ReferenceImageUploadResponse> result =
                referenceImageService.uploadReferences(batchId, List.of(img));

        assertEquals(1, result.size());
        ReferenceImageUploadResponse r = result.get(0);
        assertEquals(batchId, r.batchId());
        assertNotNull(r.previewUrl(), "上传响应应带 previewUrl");
        assertTrue(r.previewUrl().startsWith("http"));
    }

    @Test
    void 删除参考图_归属正确则行消失() {
        Long batchId = batchService.createBatch(new BatchCreateRequest("test", 1, null));
        // 直接插 DB 行（不触发 MinIO 上传），避免依赖真实 MinIO
        ReferenceImage ri = new ReferenceImage();
        ri.setBatchId(batchId);
        ri.setObjectKey("test/ref/delete_" + System.nanoTime() + ".jpg");
        ri.setSource("MANUAL");
        referenceImageMapper.insert(ri);
        Long refId = ri.getId();

        referenceImageService.deleteReference(batchId, refId);

        assertNull(referenceImageMapper.selectById(refId), "删除后记录应不存在");
    }

    @Test
    void 删除参考图_跨批次归属校验() {
        Long batchA = batchService.createBatch(new BatchCreateRequest("A", 1, null));
        Long batchB = batchService.createBatch(new BatchCreateRequest("B", 1, null));
        ReferenceImage ri = new ReferenceImage();
        ri.setBatchId(batchA);
        ri.setObjectKey("test/ref/cross_" + System.nanoTime() + ".jpg");
        ri.setSource("MANUAL");
        referenceImageMapper.insert(ri);

        // 用批次 B 去删批次 A 的图，应被拒
        assertThrows(BizException.class,
                () -> referenceImageService.deleteReference(batchB, ri.getId()));
        // 原记录仍在
        assertNotNull(referenceImageMapper.selectById(ri.getId()));
    }

    @Test
    void 删除参考图_不存在时抛异常() {
        Long batchId = batchService.createBatch(new BatchCreateRequest("test", 1, null));
        assertThrows(BizException.class,
                () -> referenceImageService.deleteReference(batchId, 99999999L));
    }
}
