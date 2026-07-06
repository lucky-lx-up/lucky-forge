package com.lucky.luckyforge.api.controller;

import com.lucky.luckyforge.application.promptbuilder.PromptBuilderService;
import com.lucky.luckyforge.application.promptbuilder.dto.PromptGenerationRequest;
import com.lucky.luckyforge.application.promptbuilder.dto.PromptGenerationResponse;
import com.lucky.luckyforge.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提示词生成端点（流水线第②环）。
 */
@RestController
@RequestMapping("/api/batches")
public class PromptBuilderController {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilderController.class);

    @Autowired
    private PromptBuilderService promptBuilderService;

    /**
     * 为指定批次生成出图提示词。
     *
     * @param batchId 路径参数：批次 id（必须已含 styleId）
     * @param request 请求体（count 可空）
     * @return 生成的提示词列表
     */
    @PostMapping("/{batchId}/prompts")
    public ResponseEntity<ApiResponse<List<PromptGenerationResponse>>> generate(
            @PathVariable Long batchId,
            @RequestBody(required = false) PromptGenerationRequest request) {
        try {
            List<PromptGenerationResponse> result =
                    promptBuilderService.generatePrompts(batchId, request);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("提示词生成失败 batchId={}", batchId, ex);
            throw new RuntimeException(ex);
        }
    }
}
