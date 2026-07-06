package com.lucky.luckyforge.api.controller;

import com.lucky.luckyforge.application.packageassembler.PackageAssemblerService;
import com.lucky.luckyforge.application.packageassembler.dto.PackageAssemblyResponse;
import com.lucky.luckyforge.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 素材打包端点（流水线第⑤环）。
 */
@RestController
@RequestMapping("/api/runs")
public class PackageAssemblerController {

    private static final Logger log = LoggerFactory.getLogger(PackageAssemblerController.class);

    @Autowired
    private PackageAssemblerService packageAssemblerService;

    /**
     * 对指定 run 的 Top N 高分图执行打包。
     *
     * @param runId 路径参数：运行 id（必须含打分结果）
     * @return 打包结果（含 package id、标题、标签、图片列表）
     */
    @PostMapping("/{runId}/package")
    public ResponseEntity<ApiResponse<PackageAssemblyResponse>> assemble(@PathVariable Long runId) {
        try {
            PackageAssemblyResponse result = packageAssemblerService.assemble(runId);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("打包失败 runId={}", runId, ex);
            throw new RuntimeException(ex);
        }
    }
}
