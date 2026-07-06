package com.lucky.luckyforge.api.controller;

import com.lucky.luckyforge.application.pipeline.PipelineOrchestratorService;
import com.lucky.luckyforge.application.pipeline.dto.PipelineResult;
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
 * 流水线编排端点。
 * <p>一键串联 5 个环节：风格提炼→提示词→出图→打分→打包。
 *
 * <p>注意：同步执行，全流程耗时约 2-5 分钟（出图是大头）。
 * 调用方需配置足够长的超时（如 5 分钟）。
 */
@RestController
@RequestMapping("/api/batches")
public class PipelineOrchestratorController {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestratorController.class);

    @Autowired
    private PipelineOrchestratorService pipelineOrchestratorService;

    /**
     * 对指定 batch 执行全流程。
     *
     * @param batchId 路径参数：批次 id（必须已含参考图）
     * @return 全流程聚合结果（含每步状态、耗时、关键产出 id）
     */
    @PostMapping("/{batchId}/pipeline")
    public ResponseEntity<ApiResponse<PipelineResult>> execute(@PathVariable Long batchId) {
        try {
            PipelineResult result = pipelineOrchestratorService.execute(batchId);
            // 即使 overallSuccess=false，也返回 200 + ApiResponse（让调用方看到哪步失败）
            // 失败细节在 result.overallMessage 与 steps 里
            String message = result.overallSuccess() ? "全流程成功" : result.overallMessage();
            return ResponseEntity.ok(new ApiResponse<>(
                    result.overallSuccess() ? ApiResponse.SUCCESS : ApiResponse.ERROR,
                    message,
                    result));
        } catch (RuntimeException ex) {
            // BizException 等（如 batch 不存在）交由 GlobalExceptionHandler
            throw ex;
        } catch (Exception ex) {
            log.error("流水线执行异常 batchId={}", batchId, ex);
            throw new RuntimeException(ex);
        }
    }
}
