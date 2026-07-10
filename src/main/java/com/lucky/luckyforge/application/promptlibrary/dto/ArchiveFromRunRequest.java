package com.lucky.luckyforge.application.promptlibrary.dto;

import java.util.List;

/**
 * 从某次 run 归档提示词到库的请求。
 *
 * <p>典型场景：在工作台结果页（/prompts/run/:runId）看到效果好，把对应提示词归档进库。
 * 归档时自动继承 run.batch.styleId 与 vertical。
 *
 * @param runId 来源运行 id（必填）
 * @param items 要归档的提示词条目（promptId 必填；note/tags 可选）
 */
public record ArchiveFromRunRequest(
        Long runId,
        List<Item> items
) {

    /**
     * 紧凑构造器：校验 runId 与 items 非空。
     */
    public ArchiveFromRunRequest {
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("runId 非法");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items 不能为空");
        }
    }

    /**
     * 单条归档项。
     *
     * @param promptId 来源提示词 id（必填，run 下的 lf_prompt.id）
     * @param note     用户备注（可空）
     * @param tags     用户标签（可空）
     */
    public record Item(
            Long promptId,
            String note,
            List<String> tags
    ) {

        /**
         * 紧凑构造器：校验 promptId。
         */
        public Item {
            if (promptId == null || promptId <= 0) {
                throw new IllegalArgumentException("promptId 非法");
            }
            if (note != null && note.length() > 500) {
                throw new IllegalArgumentException("note 过长（上限 500 字符）");
            }
        }
    }
}
