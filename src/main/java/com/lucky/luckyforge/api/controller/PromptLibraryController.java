package com.lucky.luckyforge.api.controller;

import com.lucky.luckyforge.application.promptlibrary.PromptLibraryService;
import com.lucky.luckyforge.application.promptlibrary.dto.*;
import com.lucky.luckyforge.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 提示词库端点。
 * <p>提供库条目 CRUD、从库出图（工作台）、从 run 归档、查工作台出图详情四类能力。
 *
 * <p>遵循 AGENTS.md：Controller 只做请求/响应处理，业务逻辑在 ServiceImpl，跨层传 DTO，
 * 所有方法返回 {@code ResponseEntity<ApiResponse<T>>}，异常交 GlobalExceptionHandler。
 */
@RestController
@RequestMapping("/api/prompt-library")
public class PromptLibraryController {

    private static final Logger log = LoggerFactory.getLogger(PromptLibraryController.class);

    @Autowired
    private PromptLibraryService promptLibraryService;

    /**
     * 列出库条目（可按风格或垂类过滤）。
     *
     * @param styleId  风格 id（可选）
     * @param vertical 垂类（可选）
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<PromptLibraryItemResponse>>> list(
            @RequestParam(required = false) Long styleId,
            @RequestParam(required = false) String vertical) {
        try {
            List<PromptLibraryItemResponse> result = promptLibraryService.list(styleId, vertical);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("查询提示词库失败", ex);
            throw new RuntimeException(ex);
        }
    }

    /** 查单条库条目详情 */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PromptLibraryItemResponse>> detail(@PathVariable Long id) {
        try {
            PromptLibraryItemResponse result = promptLibraryService.getById(id);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("查询提示词库详情失败 id={}", id, ex);
            throw new RuntimeException(ex);
        }
    }

    /** 手动录入库条目 */
    @PostMapping
    public ResponseEntity<ApiResponse<PromptLibraryItemResponse>> create(
            @RequestBody PromptLibraryCreateRequest request) {
        try {
            PromptLibraryItemResponse result = promptLibraryService.create(request);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("录入提示词库失败", ex);
            throw new RuntimeException(ex);
        }
    }

    /** 更新库条目的备注/标签 */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<PromptLibraryItemResponse>> update(
            @PathVariable Long id,
            @RequestBody PromptLibraryUpdateRequest request) {
        try {
            PromptLibraryItemResponse result = promptLibraryService.update(id, request);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("更新提示词库条目失败 id={}", id, ex);
            throw new RuntimeException(ex);
        }
    }

    /** 逻辑删除库条目 */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        try {
            promptLibraryService.delete(id);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("删除提示词库条目失败 id={}", id, ex);
            throw new RuntimeException(ex);
        }
    }

    /**
     * 从库中选择若干提示词触发出图（异步执行，立即返回 runId）。
     */
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<LibraryGenerateResponse>> generate(
            @RequestBody LibraryGenerateRequest request) {
        try {
            LibraryGenerateResponse result = promptLibraryService.generateFromLibrary(request);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("库出图失败", ex);
            throw new RuntimeException(ex);
        }
    }

    /**
     * 从某次 run 归档提示词到库。
     */
    @PostMapping("/archive")
    public ResponseEntity<ApiResponse<List<PromptLibraryItemResponse>>> archive(
            @RequestBody ArchiveFromRunRequest request) {
        try {
            List<PromptLibraryItemResponse> result = promptLibraryService.archiveFromRun(request);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("归档提示词失败 runId={}", request.runId(), ex);
            throw new RuntimeException(ex);
        }
    }

    /**
     * 列出提示词库出图的历史记录（前端「出图历史」标签页用）。
     *
     * @param styleId  风格 id（可选）
     * @param vertical 垂类（可选）
     */
    @GetMapping("/runs")
    public ResponseEntity<ApiResponse<List<LibraryRunSummary>>> listRuns(
            @RequestParam(required = false) Long styleId,
            @RequestParam(required = false) String vertical) {
        try {
            List<LibraryRunSummary> result = promptLibraryService.listRuns(styleId, vertical);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("查询库出图历史失败", ex);
            throw new RuntimeException(ex);
        }
    }

    /**
     * 查工作台某次出图的详情（前端结果页轮询用）。
     */
    @GetMapping("/runs/{runId}")
    public ResponseEntity<ApiResponse<LibraryRunDetail>> runDetail(@PathVariable Long runId) {
        try {
            LibraryRunDetail result = promptLibraryService.getRunDetail(runId);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("查询库出图详情失败 runId={}", runId, ex);
            throw new RuntimeException(ex);
        }
    }

    /**
     * 删除某次库出图的全部记录（含关联图片/打分/MinIO 文件）。
     * <p>若该 run 的 prompt 已归档进库则禁止删除（保护归档数据）。
     */
    @DeleteMapping("/runs/{runId}")
    public ResponseEntity<ApiResponse<Void>> deleteRun(@PathVariable Long runId) {
        try {
            promptLibraryService.deleteRun(runId);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("删除库出图历史失败 runId={}", runId, ex);
            throw new RuntimeException(ex);
        }
    }

    /**
     * 批量删除出图历史（宽容模式：跳过已归档的，只删未归档的）。
     */
    @DeleteMapping("/runs")
    public ResponseEntity<ApiResponse<BatchDeleteResult>> deleteRuns(@RequestBody java.util.List<Long> runIds) {
        try {
            BatchDeleteResult result = promptLibraryService.deleteRuns(runIds);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("批量删除库出图历史失败 runIds={}", runIds, ex);
            throw new RuntimeException(ex);
        }
    }

    /**
     * 列出所有风格（供前端筛选下拉用）。
     */
    @GetMapping("/styles")
    public ResponseEntity<ApiResponse<List<PromptLibraryService.StyleBrief>>> styles() {
        try {
            List<PromptLibraryService.StyleBrief> result = promptLibraryService.listStyles();
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("查询风格列表失败", ex);
            throw new RuntimeException(ex);
        }
    }
}
