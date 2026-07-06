package com.lucky.luckyforge.application.referenceimage.dto;

/**
 * 参考图上传响应 DTO。
 * <p>承载单张参考图上传后的落库结果（id 与 object_key），供 Controller 返回前端。
 *
 * @param id        lf_reference_image 新记录主键
 * @param batchId   所属批次 id
 * @param objectKey MinIO 对象路径
 */
public record ReferenceImageUploadResponse(
        Long id,
        Long batchId,
        String objectKey
) {

    /**
     * 紧凑构造器：校验关键字段非空。
     */
    public ReferenceImageUploadResponse {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("参考图记录 id 非法");
        }
        if (batchId == null || batchId <= 0) {
            throw new IllegalArgumentException("batchId 非法");
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey 不能为空");
        }
    }
}
