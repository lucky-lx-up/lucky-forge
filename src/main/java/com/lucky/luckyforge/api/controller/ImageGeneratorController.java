package com.lucky.luckyforge.api.controller;

import com.lucky.luckyforge.application.imagegenerator.ImageGeneratorService;
import com.lucky.luckyforge.application.imagegenerator.dto.ImageGenerationSummary;
import com.lucky.luckyforge.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 出图端点（流水线第③环）。
 */
@RestController
@RequestMapping("/api/runs")
public class ImageGeneratorController {

    private static final Logger log = LoggerFactory.getLogger(ImageGeneratorController.class);

    @Autowired
    private ImageGeneratorService imageGeneratorService;

    /**
     * 对指定 run 下所有提示词触发并发出图。
     *
     * @param runId 路径参数：运行 id
     * @return 出图汇总（含每条成功/失败明细）
     */
    @PostMapping("/{runId}/images")
    public ResponseEntity<ApiResponse<ImageGenerationSummary>> generate(@PathVariable Long runId) {
        try {
            ImageGenerationSummary result = imageGeneratorService.generateImages(runId);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("出图失败 runId={}", runId, ex);
            throw new RuntimeException(ex);
        }
    }
}
