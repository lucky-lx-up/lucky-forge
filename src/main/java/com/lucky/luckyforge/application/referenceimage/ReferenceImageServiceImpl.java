package com.lucky.luckyforge.application.referenceimage;

import com.lucky.luckyforge.application.referenceimage.dto.ReferenceImageUploadResponse;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.entity.ReferenceImage;
import com.lucky.luckyforge.infrastructure.persistence.mapper.BatchMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.ReferenceImageMapper;
import com.lucky.luckyforge.infrastructure.storage.MinioStorageService;
import com.lucky.luckyforge.infrastructure.storage.ObjectKeyBuilder;
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
        String objectKey = ObjectKeyBuilder.reference(vertical, batchId, filename);

        // MinIO 上传（不可回滚，放事务外）
        try {
            storageService.upload(objectKey, file.getBytes(),
                    file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        } catch (IOException e) {
            throw new BizException("读取参考图文件失败: " + filename);
        }

        // 写 lf_reference_image
        ReferenceImage ri = new ReferenceImage();
        ri.setBatchId(batchId);
        ri.setObjectKey(objectKey);
        ri.setSource(SOURCE_MANUAL);
        referenceImageMapper.insert(ri);

        return new ReferenceImageUploadResponse(ri.getId(), batchId, objectKey);
    }
}
