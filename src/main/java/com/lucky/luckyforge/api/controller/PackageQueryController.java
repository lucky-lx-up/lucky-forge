package com.lucky.luckyforge.api.controller;

import com.lucky.luckyforge.application.packagequery.PackageQueryService;
import com.lucky.luckyforge.application.packagequery.dto.PackageDetail;
import com.lucky.luckyforge.application.packagequery.dto.PackageSummary;
import com.lucky.luckyforge.common.response.ApiResponse;
import com.lucky.luckyforge.infrastructure.storage.MinioStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 素材包查询 + 图片预览 URL 端点。
 */
@RestController
@RequestMapping("/api")
public class PackageQueryController {

    @Autowired
    private PackageQueryService packageQueryService;

    @Autowired
    private MinioStorageService storageService;

    /** 查素材包详情（含图片 + 预签名 URL） */
    @GetMapping("/packages/{id}")
    public ResponseEntity<ApiResponse<PackageDetail>> getPackage(@PathVariable Long id) {
        PackageDetail detail = packageQueryService.getPackageDetail(id);
        return ResponseEntity.ok(ApiResponse.success(detail));
    }

    /** 查 batch 的素材包列表 */
    @GetMapping("/batches/{batchId}/packages")
    public ResponseEntity<ApiResponse<List<PackageSummary>>> listByBatch(@PathVariable Long batchId) {
        List<PackageSummary> list = packageQueryService.listPackagesByBatch(batchId);
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    /** 获取图片预签名预览 URL（前端用此 URL 直显图片） */
    @GetMapping("/images/preview-url")
    public ResponseEntity<ApiResponse<String>> previewUrl(@RequestParam String objectKey) {
        String url = storageService.getPublicUrl(objectKey);
        return ResponseEntity.ok(ApiResponse.success(url));
    }
}
