package com.lucky.luckyforge.application.batch;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lucky.luckyforge.application.batch.dto.BatchCreateRequest;
import com.lucky.luckyforge.application.batch.dto.BatchDetail;
import com.lucky.luckyforge.application.batch.dto.BatchSummary;
import com.lucky.luckyforge.application.batch.dto.ReferenceImageSummary;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.entity.ReferenceImage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Run;
import com.lucky.luckyforge.infrastructure.persistence.enums.BatchStatus;
import com.lucky.luckyforge.infrastructure.persistence.enums.Vertical;
import com.lucky.luckyforge.infrastructure.persistence.mapper.BatchMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.ReferenceImageMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.RunMapper;
import com.lucky.luckyforge.infrastructure.storage.MinioStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 批次创建与查询服务实现。
 */
@Service
public class BatchServiceImpl implements BatchService {

    /**
     * 提示词库出图自动创建的占位批次 theme 标识。
     * <p>这类批次是为复用 ImageGenerator/ImageScorer（它们强依赖 batch）而创建的，
     * 并非用户真正发起的生产批次，不应出现在批次列表中污染界面。
     * 与 {@code PromptLibraryServiceImpl.PLACEHOLDER_BATCH_THEME} 保持一致。
     */
    public static final String PLACEHOLDER_BATCH_THEME = "提示词库直接出图";

    @Autowired private BatchMapper batchMapper;
    @Autowired private RunMapper runMapper;
    @Autowired private ReferenceImageMapper referenceImageMapper;
    @Autowired private MinioStorageService storageService;

    @Override
    public Long createBatch(BatchCreateRequest request) {
        Batch batch = new Batch();
        batch.setTheme(request.theme());
        batch.setTargetCount(request.targetCount() != null && request.targetCount() > 0
                ? request.targetCount() : 4);
        batch.setVertical(request.vertical() != null && !request.vertical().isBlank()
                ? request.vertical() : Vertical.WALLPAPER.value());
        batch.setStatus(BatchStatus.DRAFT.value());
        batchMapper.insert(batch);
        return batch.getId();
    }

    @Override
    public IPage<BatchSummary> listBatches(int page, int size) {
        Page<Batch> pageParam = new Page<>(Math.max(1, page), Math.max(1, Math.min(size, 100)));
        // 按 id 倒序（最新在前）；排除提示词库出图自动创建的占位批次（避免污染批次列表）
        Page<Batch> result = batchMapper.selectPage(pageParam,
                new LambdaQueryWrapper<Batch>()
                        .ne(Batch::getTheme, PLACEHOLDER_BATCH_THEME)
                        .orderByDesc(Batch::getId));
        return result.convert(this::toSummary);
    }

    @Override
    public BatchDetail getBatchDetail(Long id) {
        if (id == null || id <= 0) {
            throw new BizException("batchId 非法");
        }
        Batch batch = batchMapper.selectById(id);
        if (batch == null) {
            throw new BizException("批次不存在: " + id);
        }
        // 查最近的 run（按 id 倒序取第一个）
        Run latestRun = runMapper.selectOne(new LambdaQueryWrapper<Run>()
                .eq(Run::getBatchId, id)
                .orderByDesc(Run::getId)
                .last("LIMIT 1"));
        return new BatchDetail(batch.getId(), batch.getTheme(), batch.getVertical(),
                batch.getTargetCount(), batch.getStatus(), batch.getStyleId(),
                latestRun != null ? latestRun.getId() : null,
                latestRun != null ? latestRun.getStatus() : null,
                latestRun != null ? latestRun.getCurrentStep() : null,
                batch.getCreatedAt());
    }

    @Override
    public List<ReferenceImageSummary> listReferenceImages(Long batchId) {
        if (batchId == null || batchId <= 0) {
            throw new BizException("batchId 非法");
        }
        List<ReferenceImage> refs = referenceImageMapper.selectList(
                new LambdaQueryWrapper<ReferenceImage>()
                        .eq(ReferenceImage::getBatchId, batchId)
                        .orderByAsc(ReferenceImage::getId));
        return refs.stream().map(r -> new ReferenceImageSummary(
                r.getId(), r.getBatchId(), r.getObjectKey(),
                storageService.getPublicUrl(r.getObjectKey()),
                r.getSource()
        )).toList();
    }

    @Override
    public void deleteBatch(Long id) {
        if (id == null || id <= 0) {
            throw new BizException("batchId 非法");
        }
        Batch batch = batchMapper.selectById(id);
        if (batch == null) {
            throw new BizException("批次不存在: " + id);
        }
        // 逻辑删除（deletedAt 写入，MyBatis-Plus 全局逻辑删除自动生效，列表查询自动过滤）
        // 关联数据（run/prompt/image/score/package）不级联删除——它们无 deletedAt 字段，且可能被引用。
        batchMapper.deleteById(id);
    }

    @Override
    public int deleteBatches(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BizException("ids 不能为空");
        }
        // 批量逻辑删除（MyBatis-Plus 的 deleteByIds 对含 deletedAt 的表自动逻辑删除）
        batchMapper.deleteByIds(ids);
        return ids.size();
    }

    private BatchSummary toSummary(Batch batch) {
        return new BatchSummary(batch.getId(), batch.getTheme(), batch.getVertical(),
                batch.getTargetCount(), batch.getStatus(), batch.getStyleId(),
                batch.getCreatedAt());
    }
}
