package com.lucky.luckyforge.api.controller;

import com.lucky.luckyforge.application.styleanalysis.StyleAnalysisService;
import com.lucky.luckyforge.application.styleanalysis.dto.StyleAnalysisResponse;
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
 * 风格提炼端点。
 * <p>流水线第一环的 REST 入口：对指定 batch 同步触发风格提炼。
 */
@RestController
@RequestMapping("/api/batches")
public class StyleAnalysisController {

    private static final Logger log = LoggerFactory.getLogger(StyleAnalysisController.class);

    @Autowired
    private StyleAnalysisService styleAnalysisService;

    /**
     * 对指定批次执行风格提炼。
     *
     * @param batchId 路径参数：批次 id
     * @return 风格提炼结果（新 style id、特征、回填后的 batchId）
     */
    @PostMapping("/{batchId}/style-analysis")
    public ResponseEntity<ApiResponse<StyleAnalysisResponse>> analyze(@PathVariable Long batchId) {
        try {
            StyleAnalysisResponse result = styleAnalysisService.analyze(batchId);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException ex) {
            // 交由 GlobalExceptionHandler 统一映射
            throw ex;
        } catch (Exception ex) {
            log.error("风格提炼失败 batchId={}", batchId, ex);
            throw new RuntimeException(ex);
        }
    }
}
