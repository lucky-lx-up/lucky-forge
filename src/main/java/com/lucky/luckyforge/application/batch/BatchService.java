package com.lucky.luckyforge.application.batch;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lucky.luckyforge.application.batch.dto.BatchCreateRequest;
import com.lucky.luckyforge.application.batch.dto.BatchDetail;
import com.lucky.luckyforge.application.batch.dto.BatchSummary;
import com.lucky.luckyforge.application.batch.dto.ReferenceImageSummary;

import java.util.List;

/**
 * 批次创建与查询服务。
 */
public interface BatchService {

    /** 创建批次 */
    Long createBatch(BatchCreateRequest request);

    /** 分页查询批次列表 */
    IPage<BatchSummary> listBatches(int page, int size);

    /** 查批次详情（含最近运行状态） */
    BatchDetail getBatchDetail(Long id);

    /** 查批次的参考图列表（含预签名 URL） */
    List<ReferenceImageSummary> listReferenceImages(Long batchId);

    /** 逻辑删除批次（deletedAt 写入，列表自动过滤；关联数据保留） */
    void deleteBatch(Long id);

    /** 批量逻辑删除批次 */
    int deleteBatches(List<Long> ids);
}
