package com.lucky.luckyforge.application.referenceimage;

import com.lucky.luckyforge.application.referenceimage.dto.ReferenceImageUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 参考图上传服务。
 * <p>承接"人工投喂参考图"的业务逻辑：校验批次存在、上传图片到 MinIO、写 lf_reference_image 记录。
 */
public interface ReferenceImageService {

    /**
     * 上传一批参考图到指定批次。
     * <p>每张图独立上传与落库；若某张失败，已成功的图保留（首版不整批回滚，避免单点失败拖累整批）。
     *
     * @param batchId 所属批次 id（必须存在）
     * @param files   上传的图片文件列表（不能为空）
     * @return 每张图对应的上传响应（顺序与入参一致）
     */
    List<ReferenceImageUploadResponse> uploadReferences(Long batchId, List<MultipartFile> files);
}
