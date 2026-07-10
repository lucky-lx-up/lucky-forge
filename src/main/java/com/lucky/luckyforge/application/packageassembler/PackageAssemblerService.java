package com.lucky.luckyforge.application.packageassembler;

import com.lucky.luckyforge.application.packageassembler.dto.PackageAssemblyResponse;

/**
 * 素材打包服务（流水线第⑤环）。
 * <p>把 run 的 Top N 高分图收口为带标题/标签的素材包。
 */
public interface PackageAssemblerService {

    /**
     * 对指定 run 执行打包。
     *
     * @param runId 运行 id（必须含打分结果）
     * @return 打包结果（含 package id、标题、标签、图片列表）
     */
    PackageAssemblyResponse assemble(Long runId);

    /**
     * 对指定 run 执行打包（指定 TopN，覆盖 batch.targetCount）。
     *
     * @param runId 运行 id（必须含打分结果）
     * @param count 打包 TopN（可空：空则回退 batch.targetCount）
     * @return 打包结果
     */
    PackageAssemblyResponse assemble(Long runId, Integer count);
}
