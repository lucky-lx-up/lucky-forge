package com.lucky.luckyforge.application.imagegenerator.dto;

/**
 * 单条提示词的出图结果。
 *
 * @param promptId         对应的 lf_prompt id
 * @param seq              prompt 序号
 * @param generatedImageId 成功时为 lf_generated_image 新记录 id；失败时为 null
 * @param objectKey        成功时的 MinIO 对象路径；失败时为 null
 * @param success          是否成功
 * @param errorMessage     失败时的错误信息；成功时为 null
 */
public record ImageGenerationResult(
        Long promptId,
        Integer seq,
        Long generatedImageId,
        String objectKey,
        boolean success,
        String errorMessage
) {
}
