package com.lucky.luckyforge.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lucky.luckyforge.application.batch.BatchService;
import com.lucky.luckyforge.application.batch.dto.BatchCreateRequest;
import com.lucky.luckyforge.application.batch.dto.BatchDetail;
import com.lucky.luckyforge.application.batch.dto.BatchSummary;
import com.lucky.luckyforge.application.batch.dto.ReferenceImageSummary;
import com.lucky.luckyforge.common.response.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 批次创建与查询端点。
 * <p>注意：批次的其他操作（风格提炼/提示词/流水线）由各自的 Controller 提供，
 * 本类只负责创建、查询、删除。
 */

/**
 * 批次创建与查询端点。
 * <p>注意：批次的其他操作（风格提炼/提示词/流水线）由各自的 Controller 提供，
 * 本类只负责创建与查询。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    @Autowired
    private BatchService batchService;

    /** 创建批次 */
    @PostMapping
    public ResponseEntity<ApiResponse<Long>> create(@RequestBody BatchCreateRequest request) {
        Long id = batchService.createBatch(request);
        return ResponseEntity.ok(ApiResponse.success(id));
    }

    /** 分页查询批次列表 */
    @GetMapping
    public ResponseEntity<ApiResponse<IPage<BatchSummary>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        IPage<BatchSummary> result = batchService.listBatches(page, size);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** 查批次详情 */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<BatchDetail>> detail(@PathVariable Long id) {
        BatchDetail detail = batchService.getBatchDetail(id);
        return ResponseEntity.ok(ApiResponse.success(detail));
    }

    /** 查批次的参考图列表（含预签名 URL，供前端预览） */
    @GetMapping("/{id}/reference-images")
    public ResponseEntity<ApiResponse<List<ReferenceImageSummary>>> referenceImages(@PathVariable Long id) {
        List<ReferenceImageSummary> list = batchService.listReferenceImages(id);
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    /** 逻辑删除批次（草稿不再展示在列表，关联数据保留） */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        batchService.deleteBatch(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** 批量逻辑删除批次（body: {"ids": [1,2,3]}） */
    @DeleteMapping
    public ResponseEntity<ApiResponse<Integer>> deleteBatch(@RequestBody java.util.List<Long> ids) {
        int count = batchService.deleteBatches(ids);
        return ResponseEntity.ok(ApiResponse.success(count));
    }
}
