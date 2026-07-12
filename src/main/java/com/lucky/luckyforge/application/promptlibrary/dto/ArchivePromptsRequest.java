package com.lucky.luckyforge.application.promptlibrary.dto;

import java.util.List;

/**
 * 按 promptId 直接归档提示词到库的请求（不需传 runId，后端自动追溯）。
 *
 * @param promptIds 来源提示词 id 列表（必填，非空）
 */
public record ArchivePromptsRequest(
        List<Long> promptIds
) {
    public ArchivePromptsRequest {
        if (promptIds == null || promptIds.isEmpty()) {
            throw new IllegalArgumentException("promptIds 不能为空");
        }
    }
}
