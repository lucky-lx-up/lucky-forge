package com.lucky.luckyforge.application.referenceimage;

import com.lucky.luckyforge.application.referenceimage.dto.ReferenceImageUploadResponse;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.common.exception.StorageException;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.entity.ReferenceImage;
import com.lucky.luckyforge.infrastructure.persistence.mapper.BatchMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.ReferenceImageMapper;
import com.lucky.luckyforge.infrastructure.storage.MinioStorageService;
import com.lucky.luckyforge.infrastructure.storage.ObjectKeyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 参考图上传服务实现。
 * <p>流程：校验 batch 存在 → 逐文件上传 MinIO + 写 lf_reference_image → 返回 DTO 列表。
 *
 * <p>事务策略：单张图内"上传 MinIO + 写库"为一组，MinIO 不可回滚故放事务外（与设计决策一致）；
 * 整批不做事务包裹（单点失败不拖累整批，已成功的图保留）。
 */
@Service
public class ReferenceImageServiceImpl implements ReferenceImageService {

    private static final Logger log = LoggerFactory.getLogger(ReferenceImageServiceImpl.class);

    /** 参考图默认来源（人工投喂） */
    private static final String SOURCE_MANUAL = "MANUAL";

    @Autowired
    private BatchMapper batchMapper;

    @Autowired
    private ReferenceImageMapper referenceImageMapper;

    @Autowired
    private MinioStorageService storageService;

    @Override
    public List<ReferenceImageUploadResponse> uploadReferences(Long batchId, List<MultipartFile> files) {
        // 校验 batch 存在
        if (batchId == null || batchId <= 0) {
            throw new BizException("batchId 非法");
        }
        Batch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BizException("批次不存在: " + batchId);
        }
        if (files == null || files.isEmpty()) {
            throw new BizException("未提供任何参考图文件");
        }

        List<ReferenceImageUploadResponse> results = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            results.add(uploadOne(batchId, batch.getVertical(), file));
        }
        return results;
    }

    @Override
    public void deleteReference(Long batchId, Long referenceId) {
        if (batchId == null || batchId <= 0) {
            throw new BizException("batchId 非法");
        }
        if (referenceId == null || referenceId <= 0) {
            throw new BizException("referenceId 非法");
        }

        // 1. 图片必须存在
        ReferenceImage ri = referenceImageMapper.selectById(referenceId);
        if (ri == null) {
            throw new BizException("参考图不存在: " + referenceId);
        }
        // 2. 必须属于该批次（防跨批次误删）
        if (!batchId.equals(ri.getBatchId())) {
            throw new BizException("参考图不属于该批次");
        }

        // 3. 先删库：保证用户视角已删除（业务一致）
        referenceImageMapper.deleteById(referenceId);

        // 4. best-effort 删 MinIO：MinIO 不可回滚，失败仅告警，孤儿对象留待后续清理
        try {
            storageService.delete(ri.getObjectKey());
        } catch (StorageException e) {
            log.warn("参考图 MinIO 对象删除失败，已删库记录 referenceId={} objectKey={}: {}",
                    referenceId, ri.getObjectKey(), e.getMessage());
        }
    }

    /**
     * 上传单张参考图：生成 object_key → 上传 MinIO → 写库 → 返回 DTO。
     */
    private ReferenceImageUploadResponse uploadOne(Long batchId, String vertical, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("参考图文件为空");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "ref-" + System.nanoTime();
        }
        // 类型校验：仅接受 image/*（前端 accept 兜底，后端再验一次防绕过）
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BizException("仅支持图片文件: " + filename);
        }

        String objectKey = ObjectKeyBuilder.reference(vertical, batchId, filename);

        // MinIO 上传（不可回滚，放事务外）
        try {
            storageService.upload(objectKey, file.getBytes(), contentType);
        } catch (IOException e) {
            throw new BizException("读取参考图文件失败: " + filename);
        }

        // 写 lf_reference_image
        ReferenceImage ri = new ReferenceImage();
        ri.setBatchId(batchId);
        ri.setObjectKey(objectKey);
        ri.setSource(SOURCE_MANUAL);
        referenceImageMapper.insert(ri);

        // 生成预签名 URL 一并返回，前端上传成功即可直接渲染
        String previewUrl = storageService.getPublicUrl(objectKey);
        return new ReferenceImageUploadResponse(ri.getId(), batchId, objectKey, previewUrl);
    }
}
