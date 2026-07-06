package com.lucky.luckyforge.application.imagegenerator;

import com.lucky.luckyforge.application.imagegenerator.dto.ImageGenerationSummary;

/**
 * 出图服务（流水线第③环）。
 * <p>拿 run 下所有提示词，虚拟线程并发调 gpt-image-2 出图，入 MinIO，写 lf_generated_image。
 */
public interface ImageGeneratorService {

    /**
     * 对指定 run 下所有提示词触发并发出图。
     *
     * @param runId 运行 id（必须含 lf_prompt）
     * @return 出图汇总（含每条成功/失败明细）
     */
    ImageGenerationSummary generateImages(Long runId);
}
