package com.lucky.luckyforge.api.controller;

import com.lucky.luckyforge.application.referenceimage.ReferenceImageService;
import com.lucky.luckyforge.application.referenceimage.dto.ReferenceImageUploadResponse;
import com.lucky.luckyforge.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 参考图上传端点。
 * <p>承接"人工投喂参考图"的 REST 入口，为 StyleAnalyzer 提供输入。
 */
@RestController
@RequestMapping("/api/batches")
public class ReferenceImageController {

    private static final Logger log = LoggerFactory.getLogger(ReferenceImageController.class);

    @Autowired
    private ReferenceImageService referenceImageService;

    /**
     * 上传参考图到指定批次（multipart 多文件）。
     *
     * @param batchId 路径参数：批次 id
     * @param files   表单字段 files：一个或多个图片文件
     * @return 上传结果列表
     */
    @PostMapping("/{batchId}/reference-images")
    public ResponseEntity<ApiResponse<List<ReferenceImageUploadResponse>>> upload(
            @PathVariable Long batchId,
            @RequestParam("files") List<MultipartFile> files) {
        try {
            List<ReferenceImageUploadResponse> result = referenceImageService.uploadReferences(batchId, files);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException ex) {
            // 交由 GlobalExceptionHandler 统一映射
            throw ex;
        } catch (Exception ex) {
            log.error("参考图上传失败 batchId={}", batchId, ex);
            throw new RuntimeException(ex);
        }
    }
}
