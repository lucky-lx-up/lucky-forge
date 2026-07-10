package com.lucky.luckyforge.application.promptlibrary.dto;

/**
 * 批量删除出图历史的结果。
 *
 * <p>宽容模式：跳过已归档的，只删未归档的。
 *
 * @param deleted 已成功删除的条数
 * @param skipped 因已归档被跳过（未删除）的条数
 */
public record BatchDeleteResult(int deleted, int skipped) {

    /**
     * 紧凑构造器：校验非负。
     */
    public BatchDeleteResult {
        if (deleted < 0) {
            deleted = 0;
        }
        if (skipped < 0) {
            skipped = 0;
        }
    }
}
