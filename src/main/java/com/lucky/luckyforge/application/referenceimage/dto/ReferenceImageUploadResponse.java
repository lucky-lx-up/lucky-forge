package com.lucky.luckyforge.application.referenceimage.dto;

/**
 * 参考图上传响应 DTO。
 * <p>承载单张参考图上传后的落库结果（id 与 object_key）及预签名预览 URL，供 Controller 返回前端。
 * <p>含 previewUrl 后，前端上传成功即可直接渲染，无需再发一次列表查询。
 *
 * @param id         lf_reference_image 新记录主键
 * @param batchId    所属批次 id
 * @param objectKey  MinIO 对象路径
 * @param previewUrl MinIO 预签名预览 URL（默认 1 小时有效）
 */
public record ReferenceImageUploadResponse(
        Long id,
        Long batchId,
        String objectKey,
        String previewUrl
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
        if (previewUrl == null || previewUrl.isBlank()) {
            throw new IllegalArgumentException("previewUrl 不能为空");
        }
    }
}
