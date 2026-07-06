package com.lucky.luckyforge.application.batch;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lucky.luckyforge.application.batch.dto.BatchCreateRequest;
import com.lucky.luckyforge.application.batch.dto.BatchDetail;
import com.lucky.luckyforge.application.batch.dto.BatchSummary;
import com.lucky.luckyforge.application.batch.dto.ReferenceImageSummary;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.entity.ReferenceImage;
import com.lucky.luckyforge.infrastructure.persistence.enums.BatchStatus;
import com.lucky.luckyforge.infrastructure.persistence.mapper.BatchMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.ReferenceImageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BatchService} 集成测试（连真实 MySQL）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BatchQueryServiceIT {

    @Autowired private BatchService batchService;
    @Autowired private BatchMapper batchMapper;
    @Autowired private ReferenceImageMapper referenceImageMapper;

    @Test
    void 创建批次_默认值兜底() {
        Long id = batchService.createBatch(new BatchCreateRequest("日落风景", null, null));
        assertNotNull(id);
        Batch saved = batchMapper.selectById(id);
        assertEquals("日落风景", saved.getTheme());
        assertEquals("WALLPAPER", saved.getVertical());
        assertEquals(4, saved.getTargetCount(), "默认 targetCount=4");
        assertEquals("DRAFT", saved.getStatus());
    }

    @Test
    void 创建批次_自定义值() {
        Long id = batchService.createBatch(new BatchCreateRequest("头像", 8, "AVATAR"));
        Batch saved = batchMapper.selectById(id);
        assertEquals("AVATAR", saved.getVertical());
        assertEquals(8, saved.getTargetCount());
    }

    @Test
    void 分页查询列表() {
        for (int i = 0; i < 3; i++) {
            batchService.createBatch(new BatchCreateRequest("主题" + i, 2, null));
        }
        IPage<BatchSummary> page = batchService.listBatches(1, 2);
        assertEquals(2, page.getRecords().size(), "每页 2 条");
        assertTrue(page.getTotal() >= 3, "总数至少 3");
    }

    @Test
    void 查详情_不存在时抛异常() {
        assertThrows(BizException.class, () -> batchService.getBatchDetail(99999999L));
    }

    @Test
    void 查详情_无run时字段为空() {
        Long id = batchService.createBatch(new BatchCreateRequest("test", 1, null));
        BatchDetail detail = batchService.getBatchDetail(id);
        assertEquals(id, detail.id());
        assertNull(detail.runId(), "新建批次无 run");
        assertNull(detail.runStatus());
    }

    @Test
    void 查参考图列表_含预览URL() {
        Long batchId = batchService.createBatch(new BatchCreateRequest("test", 1, null));
        // 插 2 张参考图
        for (int i = 0; i < 2; i++) {
            ReferenceImage ri = new ReferenceImage();
            ri.setBatchId(batchId);
            ri.setObjectKey("test/ref/" + System.nanoTime() + "_" + i + ".jpg");
            ri.setSource("MANUAL");
            referenceImageMapper.insert(ri);
        }

        java.util.List<ReferenceImageSummary> list = batchService.listReferenceImages(batchId);
        assertEquals(2, list.size());
        // 预览 URL 非空且是 http 开头
        assertNotNull(list.get(0).previewUrl());
        assertTrue(list.get(0).previewUrl().startsWith("http"));
        assertEquals("MANUAL", list.get(0).source());
    }

    @Test
    void 查参考图_无图时返回空列表() {
        Long batchId = batchService.createBatch(new BatchCreateRequest("test", 1, null));
        java.util.List<ReferenceImageSummary> list = batchService.listReferenceImages(batchId);
        assertTrue(list.isEmpty());
    }
}
