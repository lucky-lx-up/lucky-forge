package com.lucky.luckyforge.application.promptlibrary;

import com.lucky.luckyforge.application.promptlibrary.dto.*;

import java.util.List;

/**
 * 提示词库服务接口。
 *
 * <p>核心能力：
 * <ul>
 *   <li>CRUD：库条目的增删改查（挂风格、可手动录入）</li>
 *   <li>归档：从某次 run 的提示词沉淀进库（人工验证后归档）</li>
 *   <li>出图：从库中选若干提示词直接出图+打分（跳过风格分析和提示词生成，复用现有 ImageGenerator/ImageScorer）</li>
 *   <li>查询：查工作台某次出图的详情（前端结果页轮询用）+ 列出出图历史（前端「历史」标签页用）</li>
 * </ul>
 */
public interface PromptLibraryService {

    /**
     * 列出库条目（可按风格或垂类过滤）。
     *
     * @param styleId  风格 id（可空：不限风格）
     * @param vertical 垂类（可空：不限垂类）
     * @return 库条目列表（按创建时间倒序）
     */
    List<PromptLibraryItemResponse> list(Long styleId, String vertical);

    /**
     * 查单条库条目详情。
     *
     * @param id 库条目 id
     * @return 库条目详情
     */
    PromptLibraryItemResponse getById(Long id);

    /**
     * 手动录入库条目。
     *
     * @param request 录入请求
     * @return 新建的库条目
     */
    PromptLibraryItemResponse create(PromptLibraryCreateRequest request);

    /**
     * 更新库条目的备注/标签（content 与 styleId 不可改）。
     *
     * @param id      库条目 id
     * @param request 更新请求
     * @return 更新后的库条目
     */
    PromptLibraryItemResponse update(Long id, PromptLibraryUpdateRequest request);

    /**
     * 逻辑删除库条目。
     *
     * @param id 库条目 id
     */
    void delete(Long id);

    /**
     * 从某次 run 归档提示词到库（人工验证后归档，继承 run.batch.styleId 与 vertical）。
     *
     * @param request 归档请求
     * @return 归档后的库条目列表
     */
    List<PromptLibraryItemResponse> archiveFromRun(ArchiveFromRunRequest request);

    /**
     * 从库中选择若干提示词触发出图（异步执行，立即返回 runId）。
     * <p>内部创建占位 batch + run，复用 {@code ImageGeneratorService} + {@code ImageScorerService}。
     *
     * @param request 出图请求
     * @return 触发响应（含 runId，前端跳转结果页轮询）
     */
    LibraryGenerateResponse generateFromLibrary(LibraryGenerateRequest request);

    /**
     * 查工作台某次出图的详情（聚合 run 状态 + 每条 prompt 的出图/打分结果）。
     *
     * @param runId 运行 id
     * @return 出图详情
     */
    LibraryRunDetail getRunDetail(Long runId);

    /**
     * 列出提示词库出图的历史记录（前端「出图历史」标签页用）。
     * <p>本质是查所有占位批次（theme = 提示词库直接出图）及其关联 run，聚合出摘要。
     *
     * @param styleId  风格 id（可空：不限风格）
     * @param vertical 垂类（可空：不限垂类）
     * @return 出图历史摘要列表（按 run 时间倒序，最新在前）
     */
    List<LibraryRunSummary> listRuns(Long styleId, String vertical);

    /**
     * 删除某次库出图的全部记录（物理删除，含关联图片/打分/MinIO 文件）。
     * <p>按外键依赖逆序清理：score_dimension → score → generated_image → prompt → run → batch，
     * 同时删除 MinIO 里的图片物理文件。若该 run 的 prompt 已归档进库则禁止删除（保护归档数据）。
     *
     * @param runId 运行 id
     */
    void deleteRun(Long runId);

    /**
     * 批量删除出图历史（宽容模式：跳过已归档的，只删未归档的）。
     *
     * @param runIds 运行 id 列表
     * @return 删除结果（已删条数 + 跳过条数）
     */
    BatchDeleteResult deleteRuns(List<Long> runIds);

    /**
     * 列出所有风格（供前端筛选下拉用）。
     *
     * @return 风格简要列表（id + name + vertical），按 id 倒序
     */
    List<StyleBrief> listStyles();

    /**
     * 风格简要信息（仅 id/name/vertical，列表下拉用）。
     */
    record StyleBrief(Long id, String name, String vertical) {

        /**
         * 紧凑构造器：校验非空。
         */
        public StyleBrief {
            if (id == null || id <= 0) {
                throw new IllegalArgumentException("id 非法");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name 不能为空");
            }
            if (vertical == null || vertical.isBlank()) {
                throw new IllegalArgumentException("vertical 不能为空");
            }
        }
    }
}
