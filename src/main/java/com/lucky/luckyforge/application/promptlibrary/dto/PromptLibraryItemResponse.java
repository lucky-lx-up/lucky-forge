package com.lucky.luckyforge.application.promptlibrary.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提示词库条目响应（列表/详情通用）。
 *
 * @param id              库条目主键
 * @param styleId         所属风格 id
 * @param styleName       所属风格名称（前端展示用，便于在库列表中识别风格）
 * @param content         提示词正文
 * @param vertical        垂类（WALLPAPER / AVATAR / POSTER）
 * @param note            用户备注（可空）
 * @param tags            用户标签数组（可空）
 * @param sourcePromptId  来源提示词 id（可空，手动录入则为空）
 * @param usageCount      累计被工作台引用出图次数
 * @param createdAt       创建时间
 */
public record PromptLibraryItemResponse(
        Long id,
        Long styleId,
        String styleName,
        String content,
        String vertical,
        String note,
        List<String> tags,
        Long sourcePromptId,
        Integer usageCount,
        LocalDateTime createdAt
) {

    /**
     * 紧凑构造器：校验关键字段非空。
     */
    public PromptLibraryItemResponse {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("id 非法");
        }
        if (styleId == null || styleId <= 0) {
            throw new IllegalArgumentException("styleId 非法");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        if (vertical == null || vertical.isBlank()) {
            throw new IllegalArgumentException("vertical 不能为空");
        }
        if (usageCount == null || usageCount < 0) {
            usageCount = 0;
        }
    }
}
