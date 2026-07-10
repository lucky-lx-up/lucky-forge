package com.lucky.luckyforge.application.promptlibrary.dto;

import java.util.List;

/**
 * 提示词库条目手动录入请求。
 *
 * @param styleId 所属风格 id（必填）
 * @param content 提示词正文（必填，非空）
 * @param note    用户备注（可空）
 * @param tags    用户标签数组（可空）
 */
public record PromptLibraryCreateRequest(
        Long styleId,
        String content,
        String note,
        List<String> tags
) {

    /**
     * 紧凑构造器：校验必填字段。
     */
    public PromptLibraryCreateRequest {
        if (styleId == null || styleId <= 0) {
            throw new IllegalArgumentException("styleId 非法");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        if (content.length() > 8000) {
            throw new IllegalArgumentException("content 过长（上限 8000 字符）");
        }
        if (note != null && note.length() > 500) {
            throw new IllegalArgumentException("note 过长（上限 500 字符）");
        }
    }
}
