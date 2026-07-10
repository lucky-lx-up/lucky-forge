package com.lucky.luckyforge.application.promptlibrary.dto;

import java.util.List;

/**
 * 提示词库条目更新请求（仅允许改备注/标签，提示词正文与风格不可改）。
 *
 * @param note 用户备注（可空）
 * @param tags 用户标签数组（可空）
 */
public record PromptLibraryUpdateRequest(
        String note,
        List<String> tags
) {

    /**
     * 紧凑构造器：校验长度。
     */
    public PromptLibraryUpdateRequest {
        if (note != null && note.length() > 500) {
            throw new IllegalArgumentException("note 过长（上限 500 字符）");
        }
    }
}
