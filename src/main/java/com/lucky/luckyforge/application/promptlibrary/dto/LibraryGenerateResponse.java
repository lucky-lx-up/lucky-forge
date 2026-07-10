package com.lucky.luckyforge.application.promptlibrary.dto;

/**
 * 工作台出图触发响应。
 *
 * <p>出图为异步执行（虚拟线程 + run 状态持久化），本响应仅返回新创建的 runId
 * 和触发摘要，前端拿到 runId 后跳转结果页轮询 run 状态。
 *
 * @param runId     新创建的运行 id（前端跳转 /prompts/run/:runId 追踪进度）
 * @param styleId   所用风格 id
 * @param styleName 所用风格名称
 * @param vertical  垂类（决定出图尺寸：WALLPAPER 竖屏 / 其他方图）
 * @param itemCount 参与出图的提示词条数
 * @param batchId   自动创建的占位批次 id（标识来源，可在批次列表识别）
 */
public record LibraryGenerateResponse(
        Long runId,
        Long styleId,
        String styleName,
        String vertical,
        int itemCount,
        Long batchId
) {

    /**
     * 紧凑构造器：校验关键字段非空。
     */
    public LibraryGenerateResponse {
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("runId 非法");
        }
        if (styleId == null || styleId <= 0) {
            throw new IllegalArgumentException("styleId 非法");
        }
        if (batchId == null || batchId <= 0) {
            throw new IllegalArgumentException("batchId 非法");
        }
        if (vertical == null || vertical.isBlank()) {
            throw new IllegalArgumentException("vertical 不能为空");
        }
        if (itemCount <= 0) {
            throw new IllegalArgumentException("itemCount 必须为正数");
        }
    }
}
