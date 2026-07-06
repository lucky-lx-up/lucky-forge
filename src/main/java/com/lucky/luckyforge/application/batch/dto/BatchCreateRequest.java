package com.lucky.luckyforge.application.batch.dto;

/**
 * 创建批次请求。
 *
 * @param theme        本次主题/意图描述（可空，自由发挥）
 * @param targetCount  目标出图数量（可空，默认 4）
 * @param vertical     垂类（可空，默认 WALLPAPER）
 */
public record BatchCreateRequest(
        String theme,
        Integer targetCount,
        String vertical
) {
}
