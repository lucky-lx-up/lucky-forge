package com.lucky.luckyforge.application.promptlibrary.dto;

import java.util.List;

/**
 * 工作台出图请求：从提示词库选择若干条提示词直接出图（跳过风格分析和提示词生成）。
 *
 * <p>约束：所选条目必须属于同一风格（保证 vertical 一致、出图尺寸统一）。
 *
 * @param libraryItemIds 提示词库条目 id 列表（必填，非空，2-12 条）
 */
public record LibraryGenerateRequest(
        List<Long> libraryItemIds
) {

    /** 单次出图条目数下限 */
    public static final int MIN_ITEMS = 1;
    /** 单次出图条目数上限（防滥用，与 PromptGenerationRequest.MAX_COUNT 对齐） */
    public static final int MAX_ITEMS = 12;

    /**
     * 紧凑构造器：校验条目 id 列表非空且数量合法。
     */
    public LibraryGenerateRequest {
        if (libraryItemIds == null || libraryItemIds.isEmpty()) {
            throw new IllegalArgumentException("libraryItemIds 不能为空");
        }
        if (libraryItemIds.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("单次出图最多 " + MAX_ITEMS + " 条提示词");
        }
        for (Long id : libraryItemIds) {
            if (id == null || id <= 0) {
                throw new IllegalArgumentException("libraryItemIds 含非法 id");
            }
        }
    }
}
