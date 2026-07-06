package com.lucky.luckyforge.application.batch.dto;

/**
 * 参考图摘要（查询用，含预览 URL）。
 *
 * @param id          参考图 id
 * @param batchId     所属批次 id
 * @param objectKey   MinIO 对象路径
 * @param previewUrl  预签名预览 URL
 * @param source      来源（MANUAL/CRAWLER）
 */
public record ReferenceImageSummary(
        Long id,
        Long batchId,
        String objectKey,
        String previewUrl,
        String source
) {
}
