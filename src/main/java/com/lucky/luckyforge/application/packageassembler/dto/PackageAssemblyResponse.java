package com.lucky.luckyforge.application.packageassembler.dto;

import java.util.List;

/**
 * 打包结果响应。
 *
 * @param packageId 新建的 lf_package id
 * @param runId     所属运行 id
 * @param batchId   所属批次 id
 * @param title     gpt-5.5 生成的中文标题
 * @param tags      gpt-5.5 生成的标签列表
 * @param images    包内图片列表（按 sortOrder 升序，首图为封面）
 */
public record PackageAssemblyResponse(
        Long packageId,
        Long runId,
        Long batchId,
        String title,
        List<String> tags,
        List<PackageImageItem> images
) {

    /**
     * 紧凑构造器：校验关键字段。
     */
    public PackageAssemblyResponse {
        if (packageId == null || packageId <= 0) {
            throw new IllegalArgumentException("packageId 非法");
        }
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("runId 非法");
        }
        if (batchId == null || batchId <= 0) {
            throw new IllegalArgumentException("batchId 非法");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title 不能为空");
        }
        if (tags == null) {
            tags = List.of();
        }
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("images 不能为空");
        }
    }
}
