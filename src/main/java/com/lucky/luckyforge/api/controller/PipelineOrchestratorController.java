package com.lucky.luckyforge.api.controller;

import com.lucky.luckyforge.application.pipeline.PipelineOrchestratorService;
import com.lucky.luckyforge.application.pipeline.dto.PipelineResult;
import com.lucky.luckyforge.application.pipeline.dto.PipelineStatusResponse;
import com.lucky.luckyforge.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流水线编排端点。
 * <p>一键串联 5 个环节：风格提炼→提示词生成→批量出图→自动打分→素材打包。
 *
 * <p>异步执行：POST 立即返回 batchId，后台虚拟线程跑全流程（约 2-5 分钟）。
 * 前端通过 GET /api/batches/{batchId}/pipeline/status 轮询进度。
 */
@RestController
@RequestMapping("/api/batches")
public class PipelineOrchestratorController {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestratorController.class);

    @Autowired
    private PipelineOrchestratorService pipelineOrchestratorService;

    /**
     * 异步触发全流程（立即返回，后台执行）。
     *
     * @param batchId 路径参数：批次 id（必须已含参考图）
     * @return batchId（用于轮询 GET /{batchId}/pipeline/status）
     */
    @PostMapping("/{batchId}/pipeline")
    public ResponseEntity<ApiResponse<Long>> execute(@PathVariable Long batchId) {
        Long trackId = pipelineOrchestratorService.executeAsync(batchId);
        return ResponseEntity.ok(ApiResponse.success("流水线已启动，轮询 GET /" + batchId + "/pipeline/status 查进度", trackId));
    }

    /**
     * 轮询异步流水线的执行状态。
     *
     * @param batchId 路径参数：批次 id
     * @return 当前状态（RUNNING/SUCCESS/FAILED + 各步明细）
     */
    @GetMapping("/{batchId}/pipeline/status")
    public ResponseEntity<ApiResponse<PipelineStatusResponse>> status(@PathVariable Long batchId) {
        PipelineStatusResponse resp = pipelineOrchestratorService.getPipelineStatus(batchId);
        return ResponseEntity.ok(ApiResponse.success(resp));
    }
}
