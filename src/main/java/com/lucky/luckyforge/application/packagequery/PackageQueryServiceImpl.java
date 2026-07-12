package com.lucky.luckyforge.application.packagequery;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.luckyforge.application.packagequery.dto.PackageDetail;
import com.lucky.luckyforge.application.packagequery.dto.PackageImageDetail;
import com.lucky.luckyforge.application.packagequery.dto.PackageSummary;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.application.imagescorer.dto.DimensionScore;
import com.lucky.luckyforge.infrastructure.persistence.entity.GeneratedImage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Package;
import com.lucky.luckyforge.infrastructure.persistence.entity.PackageImage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Prompt;
import com.lucky.luckyforge.infrastructure.persistence.entity.Score;
import com.lucky.luckyforge.infrastructure.persistence.entity.ScoreDimension;
import com.lucky.luckyforge.infrastructure.persistence.mapper.*;
import com.lucky.luckyforge.infrastructure.storage.MinioStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 素材包查询服务实现。
 * <p>组装 package + package_image + generated_image + score，并生成预签名 URL。
 */
@Service
public class PackageQueryServiceImpl implements PackageQueryService {

    private static final Logger log = LoggerFactory.getLogger(PackageQueryServiceImpl.class);

    @Autowired private PackageMapper packageMapper;
    @Autowired private PackageImageMapper packageImageMapper;
    @Autowired private GeneratedImageMapper generatedImageMapper;
    @Autowired private ScoreMapper scoreMapper;
    @Autowired private ScoreDimensionMapper scoreDimensionMapper;
    @Autowired private PromptMapper promptMapper;
    @Autowired private MinioStorageService storageService;
    @Autowired private ObjectMapper objectMapper;

    @Override
    public PackageDetail getPackageDetail(Long packageId) {
        if (packageId == null || packageId <= 0) {
            throw new BizException("packageId 非法");
        }
        Package pkg = packageMapper.selectById(packageId);
        if (pkg == null) {
            throw new BizException("素材包不存在: " + packageId);
        }

        // 查包内图片关联（按 sortOrder 升序）
        List<PackageImage> pkgImages = packageImageMapper.selectList(
                new LambdaQueryWrapper<PackageImage>()
                        .eq(PackageImage::getPackageId, packageId)
                        .orderByAsc(PackageImage::getSortOrder));

        // 查对应的生成图 + 打分
        List<Long> imageIds = pkgImages.stream().map(PackageImage::getGeneratedImageId).toList();
        Map<Long, GeneratedImage> imageMap = imageIds.isEmpty() ? Map.of()
                : generatedImageMapper.selectList(new LambdaQueryWrapper<GeneratedImage>()
                        .in(GeneratedImage::getId, imageIds)).stream()
                .collect(Collectors.toMap(GeneratedImage::getId, g -> g));
        Map<Long, Score> scoreMap = imageIds.isEmpty() ? Map.of()
                : scoreMapper.selectList(new LambdaQueryWrapper<Score>()
                        .in(Score::getGeneratedImageId, imageIds)).stream()
                .collect(Collectors.toMap(Score::getGeneratedImageId, s -> s));

        // 查每张图对应的提示词（通过 generated_image.promptId 关联）
        List<Long> promptIds = imageMap.values().stream()
                .map(GeneratedImage::getPromptId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Prompt> promptMap = promptIds.isEmpty() ? Map.of()
                : promptMapper.selectList(new LambdaQueryWrapper<Prompt>()
                        .in(Prompt::getId, promptIds)).stream()
                .collect(Collectors.toMap(Prompt::getId, p -> p));

        // 组装图片详情（含预签名 URL + 维度分明细）
        // 先批量查所有相关 score 的维度分
        List<Long> scoreIds = scoreMap.values().stream().map(Score::getId).toList();
        Map<Long, List<ScoreDimension>> dimsByScore = scoreIds.isEmpty() ? Map.of()
                : scoreDimensionMapper.selectList(new LambdaQueryWrapper<ScoreDimension>()
                        .in(ScoreDimension::getScoreId, scoreIds)).stream()
                .collect(Collectors.groupingBy(ScoreDimension::getScoreId));

        List<PackageImageDetail> images = new ArrayList<>(pkgImages.size());
        for (PackageImage pi : pkgImages) {
            GeneratedImage gi = imageMap.get(pi.getGeneratedImageId());
            String objectKey = gi != null ? gi.getObjectKey() : null;
            String previewUrl = objectKey != null ? storageService.getPublicUrl(objectKey) : null;
            Score score = scoreMap.get(pi.getGeneratedImageId());
            // 组装维度分明细
            List<DimensionScore> dimensions = null;
            String remark = null;
            BigDecimal total = null;
            if (score != null) {
                total = score.getTotal();
                remark = score.getRemark();
                List<ScoreDimension> dims = dimsByScore.getOrDefault(score.getId(), List.of());
                dimensions = dims.stream()
                        .map(d -> new DimensionScore(d.getName(), d.getValue()))
                        .toList();
            }
            Prompt prompt = gi != null && gi.getPromptId() != null
                    ? promptMap.get(gi.getPromptId()) : null;
            images.add(new PackageImageDetail(pi.getGeneratedImageId(), objectKey,
                    pi.getSortOrder(), previewUrl, total, remark, dimensions,
                    prompt != null ? prompt.getId() : null,
                    prompt != null ? prompt.getContent() : null));
        }

        return new PackageDetail(pkg.getId(), pkg.getBatchId(), pkg.getTitle(),
                parseTags(pkg.getTags()), pkg.getStatus(), images, pkg.getCreatedAt());
    }

    @Override
    public List<PackageSummary> listPackagesByBatch(Long batchId) {
        if (batchId == null || batchId <= 0) {
            throw new BizException("batchId 非法");
        }
        List<Package> packages = packageMapper.selectList(
                new LambdaQueryWrapper<Package>()
                        .eq(Package::getBatchId, batchId)
                        .orderByDesc(Package::getId));
        List<PackageSummary> result = new ArrayList<>(packages.size());
        for (Package pkg : packages) {
            long count = packageImageMapper.selectCount(new LambdaQueryWrapper<PackageImage>()
                    .eq(PackageImage::getPackageId, pkg.getId()));
            result.add(new PackageSummary(pkg.getId(), pkg.getBatchId(), pkg.getTitle(),
                    parseTags(pkg.getTags()), pkg.getStatus(), (int) count, pkg.getCreatedAt()));
        }
        return result;
    }

    /** 解析 tags JSON 字符串为 List；解析失败返回空列表 */
    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("解析 tags 失败: {}", tagsJson, e);
            return List.of();
        }
    }
}
