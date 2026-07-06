package com.lucky.luckyforge.application.packagequery.dto;

import com.lucky.luckyforge.application.imagescorer.dto.DimensionScore;

import java.math.BigDecimal;
import java.util.List;

/**
 * 素材包内的图片项（查询用，含预览 URL + 维度分明细）。
 *
 * @param generatedImageId 生成图 id
 * @param objectKey        MinIO 对象路径
 * @param sortOrder        包内排序
 * @param previewUrl       预签名预览 URL（1 小时有效）
 * @param score            打分总分（可空）
 * @param remark           打分评语（可空）
 * @param dimensions       维度分明细（composition/color/clarity/relevance；可空）
 */
public record PackageImageDetail(
        Long generatedImageId,
        String objectKey,
        Integer sortOrder,
        String previewUrl,
        BigDecimal score,
        String remark,
        List<DimensionScore> dimensions
) {
}
