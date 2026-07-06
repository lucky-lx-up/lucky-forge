package com.lucky.luckyforge.api.controller;

import com.lucky.luckyforge.application.imagescorer.ImageScorerService;
import com.lucky.luckyforge.application.imagescorer.dto.ScoreSummary;
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
 * 自动打分端点（流水线第④环）。
 */
@RestController
@RequestMapping("/api/runs")
public class ImageScorerController {

    private static final Logger log = LoggerFactory.getLogger(ImageScorerController.class);

    @Autowired
    private ImageScorerService imageScorerService;

    /**
     * 对指定 run 下所有生成图触发打分。
     *
     * @param runId 路径参数：运行 id
     * @return 打分汇总（含每张成功/失败明细 + TopN 标识）
     */
    @PostMapping("/{runId}/scores")
    public ResponseEntity<ApiResponse<ScoreSummary>> score(@PathVariable Long runId) {
        try {
            ScoreSummary result = imageScorerService.scoreImages(runId);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("打分失败 runId={}", runId, ex);
            throw new RuntimeException(ex);
        }
    }
}
