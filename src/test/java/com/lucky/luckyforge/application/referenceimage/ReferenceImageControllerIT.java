package com.lucky.luckyforge.application.referenceimage;

import com.lucky.luckyforge.application.referenceimage.dto.ReferenceImageUploadResponse;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.entity.ReferenceImage;
import com.lucky.luckyforge.infrastructure.persistence.enums.BatchStatus;
import com.lucky.luckyforge.infrastructure.persistence.enums.Vertical;
import com.lucky.luckyforge.infrastructure.persistence.mapper.BatchMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.ReferenceImageMapper;
import com.lucky.luckyforge.infrastructure.storage.MinioStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReferenceImageService} 集成测试（连真实 MinIO + MySQL）。
 * <p>验证：上传后 MinIO 对象可下载得到原字节、lf_reference_image 有记录、批次不存在时抛 BizException。
 *
 * <p>{@code @Transactional} 自动回滚 lf_reference_image 与 lf_batch 的写入，
 * 但 MinIO 上传不可回滚（测试末尾手动 delete 清理）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReferenceImageControllerIT {

    @Autowired private ReferenceImageService referenceImageService;
    @Autowired private BatchMapper batchMapper;
    @Autowired private ReferenceImageMapper referenceImageMapper;
    @Autowired private MinioStorageService storageService;

    @Test
    void 上传成功后MinIO有图且DB有记录() {
        // 准备一个 batch
        Batch batch = newBatch();
        batchMapper.insert(batch);

        byte[] payload = "fake-image-bytes".getBytes();
        MultipartFile file = new MockMultipartFile(
                "files", "test.jpg", "image/jpeg", payload);

        List<ReferenceImageUploadResponse> result =
                referenceImageService.uploadReferences(batch.getId(), List.of(file));

        assertEquals(1, result.size());
        ReferenceImageUploadResponse resp = result.get(0);
        assertEquals(batch.getId(), resp.batchId());
        assertNotNull(resp.id());
        assertTrue(resp.objectKey().contains("reference/" + batch.getId() + "/test.jpg"));

        // DB 有记录
        ReferenceImage ri = referenceImageMapper.selectById(resp.id());
        assertNotNull(ri);
        assertEquals("MANUAL", ri.getSource());
        assertEquals(batch.getId(), ri.getBatchId());

        // MinIO 对象可下载得到原字节，清理
        byte[] downloaded = storageService.download(resp.objectKey());
        assertArrayEquals(payload, downloaded);
        storageService.delete(resp.objectKey());
    }

    @Test
    void 多文件上传() {
        Batch batch = newBatch();
        batchMapper.insert(batch);

        MultipartFile f1 = new MockMultipartFile(
                "files", "a.jpg", "image/jpeg", "a".getBytes());
        MultipartFile f2 = new MockMultipartFile(
                "files", "b.jpg", "image/jpeg", "b".getBytes());
        MultipartFile f3 = new MockMultipartFile(
                "files", "c.jpg", "image/jpeg", "c".getBytes());

        List<ReferenceImageUploadResponse> result =
                referenceImageService.uploadReferences(batch.getId(), List.of(f1, f2, f3));

        assertEquals(3, result.size());
        // 清理 MinIO
        result.forEach(r -> storageService.delete(r.objectKey()));
    }

    @Test
    void 批次不存在时抛BizException() {
        MultipartFile file = new MockMultipartFile(
                "files", "x.jpg", "image/jpeg", "x".getBytes());

        BizException ex = assertThrows(BizException.class,
                () -> referenceImageService.uploadReferences(99999999L, List.of(file)));
        assertTrue(ex.getMessage().contains("批次不存在"));
    }

    @Test
    void 空文件列表抛BizException() {
        Batch batch = newBatch();
        batchMapper.insert(batch);

        BizException ex = assertThrows(BizException.class,
                () -> referenceImageService.uploadReferences(batch.getId(), List.of()));
        assertTrue(ex.getMessage().contains("未提供"));
    }

    private Batch newBatch() {
        Batch b = new Batch();
        b.setVertical(Vertical.WALLPAPER.value());
        b.setTargetCount(1);
        b.setStatus(BatchStatus.DRAFT.value());
        return b;
    }
}
