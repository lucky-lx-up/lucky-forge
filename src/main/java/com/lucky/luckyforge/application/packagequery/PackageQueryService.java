package com.lucky.luckyforge.application.packagequery;

import com.lucky.luckyforge.application.packagequery.dto.PackageDetail;
import com.lucky.luckyforge.application.packagequery.dto.PackageSummary;

import java.util.List;

/**
 * 素材包查询服务。
 */
public interface PackageQueryService {

    /** 查素材包详情（含图片 + 预签名 URL） */
    PackageDetail getPackageDetail(Long packageId);

    /** 查 batch 的素材包列表 */
    List<PackageSummary> listPackagesByBatch(Long batchId);
}
