package com.lucky.luckyforge.application.promptlibrary;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.luckyforge.application.imagegenerator.ImageGeneratorService;
import com.lucky.luckyforge.application.imagescorer.ImageScorerService;
import com.lucky.luckyforge.application.promptlibrary.dto.*;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.infrastructure.persistence.entity.*;
import com.lucky.luckyforge.infrastructure.persistence.enums.RunStatus;
import com.lucky.luckyforge.infrastructure.persistence.mapper.*;
import com.lucky.luckyforge.infrastructure.storage.MinioStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 提示词库服务实现。
 *
 * <p>核心机制：
 * <ul>
 *   <li>CRUD：MyBatis-Plus 单表操作，tags 字段 JSON 序列化由 ObjectMapper 处理。</li>
 *   <li>归档（archiveFromRun）：从已出图打分的 run 沉淀好提示词，继承 batch.styleId 与 vertical。</li>
 *   <li>出图（generateFromLibrary）：创建占位 batch + run，把库提示词作为新 prompt 写入 lf_prompt，
 *       复用 {@code imageGeneratorService.generateImages(runId)} + {@code imageScorerService.scoreImages(runId)}，
 *       异步执行（虚拟线程 + run 状态持久化，重启不丢）。</li>
 *   <li>查询（getRunDetail）：聚合 run 状态 + prompt + generated_image + score 供前端结果页轮询。</li>
 * </ul>
 *
 * <p>遵循 AGENTS.md：ServiceImpl 用 Mapper 方法访问数据，跨服务传 DTO，多次连续写库用 @Transactional。
 */
@Service
public class PromptLibraryServiceImpl implements PromptLibraryService {

    private static final Logger log = LoggerFactory.getLogger(PromptLibraryServiceImpl.class);

    /** 占位批次的 theme 标识（让用户在批次列表识别这是库出图产生的） */
    private static final String PLACEHOLDER_BATCH_THEME = "提示词库直接出图";

    @Autowired private PromptLibraryItemMapper promptLibraryItemMapper;
    @Autowired private StyleMapper styleMapper;
    @Autowired private BatchMapper batchMapper;
    @Autowired private RunMapper runMapper;
    @Autowired private PromptMapper promptMapper;
    @Autowired private GeneratedImageMapper generatedImageMapper;
    @Autowired private ScoreMapper scoreMapper;
    @Autowired private ScoreDimensionMapper scoreDimensionMapper;
    @Autowired private ImageGeneratorService imageGeneratorService;
    @Autowired private ImageScorerService imageScorerService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private MinioStorageService storageService;

    // ==================== CRUD ====================

    @Override
    public List<PromptLibraryItemResponse> list(Long styleId, String vertical) {
        LambdaQueryWrapper<PromptLibraryItem> qw = new LambdaQueryWrapper<>();
        if (styleId != null && styleId > 0) {
            qw.eq(PromptLibraryItem::getStyleId, styleId);
        }
        if (vertical != null && !vertical.isBlank()) {
            qw.eq(PromptLibraryItem::getVertical, vertical);
        }
        qw.orderByDesc(PromptLibraryItem::getId);
        List<PromptLibraryItem> items = promptLibraryItemMapper.selectList(qw);
        if (items.isEmpty()) {
            return List.of();
        }
        // 批量查风格名（避免 N+1）
        Set<Long> styleIds = items.stream().map(PromptLibraryItem::getStyleId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> styleNameMap = loadStyleNames(styleIds);
        return items.stream()
                .map(it -> toItemResponse(it, styleNameMap.get(it.getStyleId())))
                .toList();
    }

    @Override
    public PromptLibraryItemResponse getById(Long id) {
        PromptLibraryItem item = requireItem(id);
        String styleName = loadStyleNames(Set.of(item.getStyleId())).get(item.getStyleId());
        return toItemResponse(item, styleName);
    }

    @Override
    @Transactional
    public PromptLibraryItemResponse create(PromptLibraryCreateRequest request) {
        Style style = styleMapper.selectById(request.styleId());
        if (style == null) {
            throw new BizException("风格不存在: " + request.styleId());
        }
        PromptLibraryItem item = new PromptLibraryItem();
        item.setStyleId(style.getId());
        item.setContent(request.content());
        item.setVertical(style.getVertical());
        item.setNote(request.note());
        item.setTags(toTagsJson(request.tags()));
        item.setUsageCount(0);
        promptLibraryItemMapper.insert(item);
        return toItemResponse(item, style.getName());
    }

    @Override
    @Transactional
    public PromptLibraryItemResponse update(Long id, PromptLibraryUpdateRequest request) {
        PromptLibraryItem item = requireItem(id);
        item.setNote(request.note());
        item.setTags(toTagsJson(request.tags()));
        promptLibraryItemMapper.updateById(item);
        String styleName = loadStyleNames(Set.of(item.getStyleId())).get(item.getStyleId());
        return toItemResponse(item, styleName);
    }

    @Override
    public void delete(Long id) {
        PromptLibraryItem item = requireItem(id);
        // 逻辑删除：MyBatis-Plus 全局逻辑删除会在 updateById 时自动写 deletedAt，
        // 但为确保生效，这里用 deleteById（MyBatis-Plus 会对逻辑删除表转为 update set deletedAt=now）
        promptLibraryItemMapper.deleteById(item.getId());
    }

    // ==================== 归档（从 run 沉淀进库） ====================

    @Override
    @Transactional
    public List<PromptLibraryItemResponse> archiveFromRun(ArchiveFromRunRequest request) {
        // 1. 校验 run
        Run run = runMapper.selectById(request.runId());
        if (run == null) {
            throw new BizException("运行不存在: " + request.runId());
        }
        // 2. 校验 batch 有 styleId（归档必须挂风格）
        Batch batch = batchMapper.selectById(run.getBatchId());
        if (batch == null) {
            throw new BizException("运行关联批次不存在: " + run.getBatchId());
        }
        if (batch.getStyleId() == null) {
            throw new BizException("批次未提炼风格，无法归档（归档必须挂风格）");
        }
        Style style = styleMapper.selectById(batch.getStyleId());
        if (style == null) {
            throw new BizException("风格记录不存在: " + batch.getStyleId());
        }
        // 3. 查 run 下所有 prompt，建 promptId -> content 映射
        List<Prompt> prompts = promptMapper.selectList(
                new LambdaQueryWrapper<Prompt>().eq(Prompt::getRunId, run.getId()));
        Map<Long, String> promptContentMap = prompts.stream()
                .collect(Collectors.toMap(Prompt::getId, Prompt::getContent, (a, b) -> a));

        // 4. 逐条归档
        List<PromptLibraryItem> archived = new ArrayList<>(request.items().size());
        for (ArchiveFromRunRequest.Item reqItem : request.items()) {
            String content = promptContentMap.get(reqItem.promptId());
            if (content == null) {
                throw new BizException("提示词不在该运行下: promptId=" + reqItem.promptId());
            }
            PromptLibraryItem lib = new PromptLibraryItem();
            lib.setStyleId(style.getId());
            lib.setContent(content);
            lib.setVertical(style.getVertical());
            lib.setNote(reqItem.note());
            lib.setTags(toTagsJson(reqItem.tags()));
            lib.setSourcePromptId(reqItem.promptId());
            lib.setUsageCount(0);
            promptLibraryItemMapper.insert(lib);
            archived.add(lib);
        }
        return archived.stream()
                .map(it -> toItemResponse(it, style.getName()))
                .toList();
    }

    // ==================== 出图（从库直接出图） ====================

    @Override
    public LibraryGenerateResponse generateFromLibrary(LibraryGenerateRequest request) {
        // 1. 校验库条目并加载
        List<PromptLibraryItem> items = new ArrayList<>(request.libraryItemIds().size());
        for (Long id : request.libraryItemIds()) {
            PromptLibraryItem it = promptLibraryItemMapper.selectById(id);
            if (it == null) {
                throw new BizException("提示词库条目不存在: " + id);
            }
            items.add(it);
        }
        // 2. 校验所有条目同风格（保证 vertical 一致、出图尺寸统一）
        Set<Long> styleIds = items.stream().map(PromptLibraryItem::getStyleId)
                .collect(Collectors.toSet());
        if (styleIds.size() != 1) {
            throw new BizException("所选提示词必须属于同一风格（当前涉及 " + styleIds.size() + " 个风格）");
        }
        Long styleId = styleIds.iterator().next();
        Style style = styleMapper.selectById(styleId);
        if (style == null) {
            throw new BizException("风格不存在: " + styleId);
        }

        // 3-5. 用编程式事务原子地创建：占位 batch + run + 库提示词对应的 prompt
        // （这三组写入要么全成功要么全失败，避免留下孤儿数据；事务提交后异步线程才能查到）
        final Long[] runIdHolder = new Long[1];
        final Long[] batchIdHolder = new Long[1];
        final Long finalStyleId = styleId;
        final String finalVertical = style.getVertical();
        final List<PromptLibraryItem> finalItems = items;
        transactionTemplate.executeWithoutResult(status -> {
            // 3. 创建占位 batch（ImageGenerator/Scorer 强依赖 batch 拿 vertical + targetCount）
            Batch placeholderBatch = new Batch();
            placeholderBatch.setStyleId(finalStyleId);
            placeholderBatch.setVertical(finalVertical);
            placeholderBatch.setTheme(PLACEHOLDER_BATCH_THEME);
            placeholderBatch.setTargetCount(finalItems.size()); // 影响 scorer 的 topN
            placeholderBatch.setStatus("RUNNING");
            batchMapper.insert(placeholderBatch);
            batchIdHolder[0] = placeholderBatch.getId();

            // 4. 创建 run（RUNNING/GENERATE）
            Run run = new Run();
            run.setBatchId(placeholderBatch.getId());
            run.setStatus(RunStatus.RUNNING.value());
            run.setCurrentStep("GENERATE");
            run.setStartedAt(LocalDateTime.now());
            runMapper.insert(run);
            runIdHolder[0] = run.getId();

            // 5. 把库提示词作为新 prompt 写入 lf_prompt（runId=新 run.id）
            int seq = 1;
            for (PromptLibraryItem it : finalItems) {
                Prompt p = new Prompt();
                p.setRunId(run.getId());
                p.setSeq(seq++);
                p.setContent(it.getContent());
                promptMapper.insert(p);
            }
        });

        final Long runId = runIdHolder[0];
        final Long batchId = batchIdHolder[0];
        final List<Long> libraryItemIds = items.stream().map(PromptLibraryItem::getId).toList();

        // 6. 异步执行出图 + 打分（复用现有服务，run 状态持久化，重启不丢）
        // 用 Thread.startVirtualThread 启动即返回（不阻塞请求线程），run 状态通过 lf_run 表追踪。
        // 注：原 PipelineOrchestrator 用 try-with-resources 包 executor，其 close() 会等待任务完成；
        //     工作台要求触发后立即返回 runId 让前端轮询，故这里用 startVirtualThread 真异步。
        final Long fRunId = runId;
        final List<Long> fLibraryItemIds = libraryItemIds;
        Thread.startVirtualThread(() -> executeGenerateAndScore(fRunId, fLibraryItemIds, finalStyleId));

        return new LibraryGenerateResponse(
                runId, finalStyleId, style.getName(),
                finalVertical, items.size(), batchId);
    }

    /**
     * 异步执行出图 + 打分，结束后统一 finalize run。
     * <p>复用 {@code imageGeneratorService.generateImages} + {@code imageScorerService.scoreImages}。
     * 失败时标 run FAILED；部分失败（出图成功部分、打分部分失败）由各 Service 自行记录 run.error，
     * 本方法负责在出图全失败时标 FAILED，其余情况标 SUCCESS（与原流水线一致）。
     */
    private void executeGenerateAndScore(Long runId,
                                         List<Long> libraryItemIds, Long styleId) {
        try {
            // 出图（复用现有逻辑：虚拟线程并发 + Semaphore + 重试 + MinIO 上传 + 写库）
            var imgSummary = imageGeneratorService.generateImages(runId);
            if (imgSummary.succeeded() == 0) {
                log.warn("库出图全部失败 runId={}", runId);
                finalizeRun(runId, RunStatus.FAILED, "出图全失败（成功 0/" + imgSummary.total() + "）");
                return;
            }
            // 打分（复用现有逻辑：虚拟线程并发 + 多模态调 gpt-5.5 + 覆盖式写库）
            try {
                imageScorerService.scoreImages(runId);
            } catch (BizException e) {
                // 打分全失败不致命（与原流水线一致：run.error 由 scorer 自身记录），仍标 SUCCESS
                log.warn("库出图打分失败 runId={}: {}", runId, e.getMessage());
            }
            // 累加 usage_count（用编程式事务：本方法从虚拟线程内被自调用，
            // @Transactional AOP 代理失效，编程式事务是 Spring 官方推荐方案，与 ImageScorer 一致）
            incrementUsageCount(libraryItemIds);
            finalizeRun(runId, RunStatus.SUCCESS, null);
        } catch (Exception e) {
            log.error("库出图执行异常 runId={}", runId, e);
            finalizeRun(runId, RunStatus.FAILED, "执行异常: " + e.getMessage());
        }
    }

    /** 批量累加库条目的 usage_count（编程式事务保护多次连续写库） */
    private void incrementUsageCount(List<Long> libraryItemIds) {
        transactionTemplate.executeWithoutResult(status -> {
            for (Long id : libraryItemIds) {
                PromptLibraryItem it = promptLibraryItemMapper.selectById(id);
                if (it != null) {
                    int cur = it.getUsageCount() == null ? 0 : it.getUsageCount();
                    it.setUsageCount(cur + 1);
                    promptLibraryItemMapper.updateById(it);
                }
            }
        });
    }

    /** 定 run 终态，同时同步占位 batch 的状态（RUNNING→DONE/FAILED）保持数据一致 */
    private void finalizeRun(Long runId, RunStatus status, String error) {
        Run run = runMapper.selectById(runId);
        if (run == null) return;
        run.setStatus(status.value());
        run.setFinishedAt(LocalDateTime.now());
        if (error != null) {
            run.setError(error);
        }
        runMapper.updateById(run);

        // 同步占位 batch 状态（batch.status 初始为 RUNNING，出图结束后应为 DONE/FAILED）
        Batch batch = batchMapper.selectById(run.getBatchId());
        if (batch != null) {
            // run 成功则 batch DONE，run 失败则 batch FAILED
            batch.setStatus(RunStatus.SUCCESS.equals(status) ? "DONE" : "FAILED");
            batchMapper.updateById(batch);
        }
    }

    // ==================== 查询（工作台结果页） ====================

    @Override
    public LibraryRunDetail getRunDetail(Long runId) {
        if (runId == null || runId <= 0) {
            throw new BizException("runId 非法");
        }
        Run run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException("运行不存在: " + runId);
        }
        Batch batch = batchMapper.selectById(run.getBatchId());
        String styleName = null;
        Long styleId = batch != null ? batch.getStyleId() : null;
        String vertical = batch != null ? batch.getVertical() : null;
        if (styleId != null) {
            Style style = styleMapper.selectById(styleId);
            if (style != null) {
                styleName = style.getName();
                vertical = style.getVertical(); // 以风格 vertical 为准
            }
        }

        // 查 run 下所有 prompt（按 seq 升序）
        List<Prompt> prompts = promptMapper.selectList(
                new LambdaQueryWrapper<Prompt>()
                        .eq(Prompt::getRunId, runId)
                        .orderByAsc(Prompt::getSeq));
        if (prompts.isEmpty()) {
            return new LibraryRunDetail(runId, run.getStatus(), run.getCurrentStep(),
                    run.getError(), styleId, styleName, vertical, List.of(),
                    run.getStartedAt(), run.getFinishedAt());
        }

        // 批量查出图（promptId -> generatedImage）
        List<Long> promptIds = prompts.stream().map(Prompt::getId).toList();
        List<GeneratedImage> images = generatedImageMapper.selectList(
                new LambdaQueryWrapper<GeneratedImage>().in(GeneratedImage::getPromptId, promptIds));
        Map<Long, GeneratedImage> imgByPrompt = images.stream()
                .collect(Collectors.toMap(GeneratedImage::getPromptId, g -> g, (a, b) -> a));

        // 批量查打分（generatedImageId -> score）
        Map<Long, Score> scoreByImg = Map.of();
        Map<Long, List<ScoreDimension>> dimsByScore = Map.of();
        if (!images.isEmpty()) {
            List<Long> imgIds = images.stream().map(GeneratedImage::getId).toList();
            List<Score> scores = scoreMapper.selectList(
                    new LambdaQueryWrapper<Score>().in(Score::getGeneratedImageId, imgIds));
            scoreByImg = scores.stream()
                    .collect(Collectors.toMap(Score::getGeneratedImageId, s -> s, (a, b) -> a));
            if (!scores.isEmpty()) {
                List<Long> scoreIds = scores.stream().map(Score::getId).toList();
                List<ScoreDimension> dims = scoreDimensionMapper.selectList(
                        new LambdaQueryWrapper<ScoreDimension>().in(ScoreDimension::getScoreId, scoreIds));
                dimsByScore = dims.stream().collect(Collectors.groupingBy(ScoreDimension::getScoreId));
            }
        }

        // 查已归档的 promptId（判断每条 prompt 是否已归档进库）
        Set<Long> archivedPromptIds = promptLibraryItemMapper.selectList(
                new LambdaQueryWrapper<PromptLibraryItem>()
                        .in(PromptLibraryItem::getSourcePromptId, promptIds)
                        .isNotNull(PromptLibraryItem::getSourcePromptId))
                .stream().map(PromptLibraryItem::getSourcePromptId)
                .filter(Objects::nonNull).collect(Collectors.toSet());

        // 组装每条 prompt 的结果
        List<LibraryRunDetail.PromptWithResult> promptResults = new ArrayList<>(prompts.size());
        for (Prompt p : prompts) {
            GeneratedImage img = imgByPrompt.get(p.getId());
            Score score = img != null ? scoreByImg.get(img.getId()) : null;
            List<LibraryRunDetail.DimensionBrief> dimBriefs = List.of();
            if (score != null && dimsByScore.containsKey(score.getId())) {
                dimBriefs = dimsByScore.get(score.getId()).stream()
                        .map(d -> new LibraryRunDetail.DimensionBrief(d.getName(), d.getValue()))
                        .toList();
            }
            promptResults.add(new LibraryRunDetail.PromptWithResult(
                    p.getId(), p.getSeq(), p.getContent(),
                    img != null ? img.getId() : null,
                    img != null ? img.getObjectKey() : null,
                    score != null ? score.getTotal() : null,
                    score != null ? score.getRemark() : null,
                    dimBriefs,
                    archivedPromptIds.contains(p.getId())
            ));
        }

        return new LibraryRunDetail(runId, run.getStatus(), run.getCurrentStep(),
                run.getError(), styleId, styleName, vertical, promptResults,
                run.getStartedAt(), run.getFinishedAt());
    }

    @Override
    public List<LibraryRunSummary> listRuns(Long styleId, String vertical) {
        // 1. 查所有占位批次（theme = 提示词库直接出图），可选按 styleId/vertical 过滤
        LambdaQueryWrapper<Batch> batchQw = new LambdaQueryWrapper<Batch>()
                .eq(Batch::getTheme, PLACEHOLDER_BATCH_THEME)
                .orderByDesc(Batch::getId);
        if (styleId != null && styleId > 0) {
            batchQw.eq(Batch::getStyleId, styleId);
        }
        if (vertical != null && !vertical.isBlank()) {
            batchQw.eq(Batch::getVertical, vertical);
        }
        List<Batch> batches = batchMapper.selectList(batchQw);
        if (batches.isEmpty()) {
            return List.of();
        }

        // 2. 批量查关联 run（一个占位 batch 恰好对应一个 run，但用批量查避免 N+1）
        List<Long> batchIds = batches.stream().map(Batch::getId).toList();
        List<Run> runs = runMapper.selectList(
                new LambdaQueryWrapper<Run>().in(Run::getBatchId, batchIds));
        // batchId -> run（占位 batch 只有一个 run，取 id 最大者兜底）
        Map<Long, Run> runByBatch = runs.stream()
                .collect(Collectors.toMap(Run::getBatchId, r -> r, (a, b) ->
                        a.getId() > b.getId() ? a : b));

        // 3. 批量解析风格名（避免 N+1）
        Set<Long> styleIds = batches.stream().map(Batch::getStyleId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> styleNameMap = loadStyleNames(styleIds);

        // 4. 批量查每个 run 的 prompt（按 seq 升序，便于取首图）
        List<Long> runIds = runs.stream().map(Run::getId).toList();
        Map<Long, List<Prompt>> promptsByRun = promptMapper.selectList(
                        new LambdaQueryWrapper<Prompt>()
                                .in(Prompt::getRunId, runIds)
                                .orderByAsc(Prompt::getSeq))
                .stream().collect(Collectors.groupingBy(Prompt::getRunId));

        // 5. 一次性批量查所有 run 的全部生成图（同时服务于：取首图 + 算平均分，避免重复查询）
        List<Long> allPromptIds = promptsByRun.values().stream()
                .flatMap(List::stream).map(Prompt::getId).toList();
        List<GeneratedImage> allImgs = List.of();
        if (!allPromptIds.isEmpty()) {
            allImgs = generatedImageMapper.selectList(
                    new LambdaQueryWrapper<GeneratedImage>().in(GeneratedImage::getPromptId, allPromptIds));
        }
        // promptId -> runId 反查映射
        Map<Long, Long> runIdByPromptId = new HashMap<>();
        for (Map.Entry<Long, List<Prompt>> e : promptsByRun.entrySet()) {
            for (Prompt p : e.getValue()) {
                runIdByPromptId.put(p.getId(), e.getKey());
            }
        }
        // imageId -> runId 映射 + 每个 run 的全量图（用于首图与均分）
        Map<Long, Long> runIdByImageId = new HashMap<>();
        Map<Long, List<GeneratedImage>> imgsByRun = new HashMap<>();
        for (GeneratedImage gi : allImgs) {
            Long runIdKey = runIdByPromptId.get(gi.getPromptId());
            if (runIdKey == null) continue;
            runIdByImageId.put(gi.getId(), runIdKey);
            imgsByRun.computeIfAbsent(runIdKey, k -> new ArrayList<>()).add(gi);
        }

        // 6. 批量查所有相关打分，按 runId 分组求均值
        Map<Long, BigDecimal> avgScoreByRun = Map.of();
        List<Long> allImgIds = allImgs.stream().map(GeneratedImage::getId).toList();
        if (!allImgIds.isEmpty()) {
            List<Score> scores = scoreMapper.selectList(
                    new LambdaQueryWrapper<Score>().in(Score::getGeneratedImageId, allImgIds));
            Map<Long, BigDecimal> sumByRun = new HashMap<>();
            Map<Long, Integer> cntByRun = new HashMap<>();
            for (Score s : scores) {
                Long runIdKey = runIdByImageId.get(s.getGeneratedImageId());
                if (runIdKey == null || s.getTotal() == null) continue;
                sumByRun.merge(runIdKey, s.getTotal(), BigDecimal::add);
                cntByRun.merge(runIdKey, 1, Integer::sum);
            }
            avgScoreByRun = sumByRun.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey,
                            e -> e.getValue().divide(BigDecimal.valueOf(cntByRun.get(e.getKey())), 2, RoundingMode.HALF_UP)));
        }

        // 7. 按 batch 倒序组装结果（batch.id desc ≈ run 时间倒序，最新在前）
        List<LibraryRunSummary> result = new ArrayList<>(batches.size());
        for (Batch batch : batches) {
            Run run = runByBatch.get(batch.getId());
            if (run == null) {
                // 极少见：占位 batch 无关联 run（数据异常或并发未落库），跳过
                continue;
            }
            List<Prompt> runPrompts = promptsByRun.getOrDefault(run.getId(), List.of());
            // 首图：该 run 下 seq 最小（runPrompts 已按 seq 升序，get(0) 即首图对应 prompt）的图
            Long firstPromptId = !runPrompts.isEmpty() ? runPrompts.get(0).getId() : null;
            String firstObjectKey = null;
            if (firstPromptId != null) {
                for (GeneratedImage gi : imgsByRun.getOrDefault(run.getId(), List.of())) {
                    if (firstPromptId.equals(gi.getPromptId())) {
                        firstObjectKey = gi.getObjectKey();
                        break;
                    }
                }
            }
            String firstPreviewUrl = firstObjectKey != null
                    ? storageService.getPublicUrl(firstObjectKey) : null;
            String styleName = batch.getStyleId() != null ? styleNameMap.get(batch.getStyleId()) : null;

            result.add(new LibraryRunSummary(
                    run.getId(),
                    run.getStatus(),
                    batch.getStyleId(),
                    styleName,
                    batch.getVertical(),
                    batch.getTargetCount() != null ? batch.getTargetCount() : runPrompts.size(),
                    firstObjectKey,
                    firstPreviewUrl,
                    avgScoreByRun.get(run.getId()),
                    run.getStartedAt(),
                    run.getFinishedAt()
            ));
        }
        return result;
    }

    @Override
    public void deleteRun(Long runId) {
        if (runId == null || runId <= 0) {
            throw new BizException("runId 非法");
        }
        Run run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException("运行不存在: " + runId);
        }

        RunDeleteContext ctx = collectRunDeleteContext(run);

        // 归档保护：若有 prompt 已归档进库，禁止删除（保护用户沉淀的好提示词）
        if (ctx.archivedCount > 0) {
            throw new BizException("该出图结果已归档进提示词库（" + ctx.archivedCount + " 条），请先删除对应库条目后再删除历史记录");
        }

        // 编程式事务：按外键依赖逆序物理删除全部 DB 数据（一次提交）
        transactionTemplate.executeWithoutResult(status -> deleteRunDataInTx(ctx));

        // 删 MinIO 物理图片（DB 事务提交后执行；单张失败不中断，记 warn）
        deleteMinioObjects(ctx.objectKeys);
    }

    @Override
    public BatchDeleteResult deleteRuns(List<Long> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            throw new BizException("runIds 不能为空");
        }
        // 去重 + 过滤非法 id
        List<Long> validIds = runIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (validIds.isEmpty()) {
            throw new BizException("runIds 无有效值");
        }

        // 先收集所有上下文（含归档检查），分出待删 vs 跳过
        List<RunDeleteContext> toDelete = new ArrayList<>();
        int skipped = 0;
        for (Long runId : validIds) {
            Run run = runMapper.selectById(runId);
            if (run == null) {
                // run 不存在，跳过（不计数，避免误导）
                continue;
            }
            RunDeleteContext ctx = collectRunDeleteContext(run);
            if (ctx.archivedCount > 0) {
                // 宽容模式：已归档的跳过
                skipped++;
            } else {
                toDelete.add(ctx);
            }
        }

        if (toDelete.isEmpty()) {
            return new BatchDeleteResult(0, skipped);
        }

        // 一个事务包住全部删除（要么全删成功要么全回滚）
        final List<RunDeleteContext> finalToDelete = toDelete;
        transactionTemplate.executeWithoutResult(status -> {
            for (RunDeleteContext ctx : finalToDelete) {
                deleteRunDataInTx(ctx);
            }
        });

        // 事务提交后批量删 MinIO 图片
        List<String> allObjectKeys = toDelete.stream()
                .flatMap(ctx -> ctx.objectKeys.stream()).toList();
        deleteMinioObjects(allObjectKeys);

        return new BatchDeleteResult(toDelete.size(), skipped);
    }

    /**
     * 收集一个 run 的删除上下文（prompts/images/scores/objectKeys/归档数/batchId）。
     * <p>供 deleteRun（单条）和 deleteRuns（批量）复用，避免重复查询。
     */
    private RunDeleteContext collectRunDeleteContext(Run run) {
        Long runId = run.getId();
        // 查 prompt
        List<Prompt> prompts = promptMapper.selectList(
                new LambdaQueryWrapper<Prompt>().eq(Prompt::getRunId, runId));
        List<Long> promptIds = prompts.stream().map(Prompt::getId).toList();
        List<Long> promptIdArg = promptIds.isEmpty() ? List.of(-1L) : promptIds;

        // 归档数
        long archivedCount = promptLibraryItemMapper.selectCount(
                new LambdaQueryWrapper<PromptLibraryItem>()
                        .in(PromptLibraryItem::getSourcePromptId, promptIdArg)
                        .isNotNull(PromptLibraryItem::getSourcePromptId));

        // 查图片
        List<GeneratedImage> images = generatedImageMapper.selectList(
                new LambdaQueryWrapper<GeneratedImage>().in(GeneratedImage::getPromptId, promptIdArg));
        List<String> objectKeys = images.stream()
                .map(GeneratedImage::getObjectKey).filter(Objects::nonNull).toList();
        List<Long> imgIds = images.stream().map(GeneratedImage::getId).toList();
        List<Long> imgIdArg = imgIds.isEmpty() ? List.of(-1L) : imgIds;

        // 查打分
        List<Score> scores = imgIds.isEmpty() ? List.of() : scoreMapper.selectList(
                new LambdaQueryWrapper<Score>().in(Score::getGeneratedImageId, imgIdArg));
        List<Long> scoreIds = scores.stream().map(Score::getId).toList();
        List<Long> scoreIdArg = scoreIds.isEmpty() ? List.of(-1L) : scoreIds;

        return new RunDeleteContext(runId, run.getBatchId(), promptIds, promptIdArg,
                imgIds, imgIdArg, scoreIds, scoreIdArg, objectKeys, archivedCount);
    }

    /**
     * 在当前事务内删除一个 run 的全部 DB 数据（按外键依赖逆序）。
     * <p>调用方负责包事务。不含归档检查、不含 MinIO 删除。
     */
    private void deleteRunDataInTx(RunDeleteContext ctx) {
        // 1. score_dimension（叶子，依赖 score）
        if (!ctx.scoreIds.isEmpty()) {
            scoreDimensionMapper.delete(new LambdaQueryWrapper<ScoreDimension>()
                    .in(ScoreDimension::getScoreId, ctx.scoreIdArg));
        }
        // 2. score（依赖 generated_image）
        if (!ctx.imgIds.isEmpty()) {
            scoreMapper.delete(new LambdaQueryWrapper<Score>()
                    .in(Score::getGeneratedImageId, ctx.imgIdArg));
        }
        // 3. generated_image（依赖 prompt）
        if (!ctx.promptIds.isEmpty()) {
            generatedImageMapper.delete(new LambdaQueryWrapper<GeneratedImage>()
                    .in(GeneratedImage::getPromptId, ctx.promptIdArg));
        }
        // 4. prompt（依赖 run）
        if (!ctx.promptIds.isEmpty()) {
            promptMapper.delete(new LambdaQueryWrapper<Prompt>()
                    .eq(Prompt::getRunId, ctx.runId));
        }
        // 5. run（依赖 batch）
        runMapper.deleteById(ctx.runId);
        // 6. batch（占位批次最后删；batch 含 deletedAt，delete 会被 MyBatis-Plus
        // 改写为逻辑删除——占位 batch 从批次列表查询消失即可，符合预期）
        if (ctx.batchId != null) {
            batchMapper.delete(new LambdaQueryWrapper<Batch>().eq(Batch::getId, ctx.batchId));
        }
    }

    /**
     * 删除一批 MinIO 物理图片（DB 事务提交后调用；单张失败不中断，记 warn）。
     */
    private void deleteMinioObjects(List<String> objectKeys) {
        for (String key : objectKeys) {
            try {
                storageService.delete(key);
            } catch (Exception e) {
                log.warn("删除 MinIO 图片失败（DB 数据已清，残留孤儿文件）objectKey={}: {}", key, e.getMessage());
            }
        }
    }

    /** 单个 run 的删除上下文（私有数据载体，避免方法间传一堆参数） */
    private record RunDeleteContext(
            Long runId, Long batchId,
            List<Long> promptIds, List<Long> promptIdArg,
            List<Long> imgIds, List<Long> imgIdArg,
            List<Long> scoreIds, List<Long> scoreIdArg,
            List<String> objectKeys, long archivedCount
    ) {}

    // ==================== 工具方法 ====================

    /** 校验库条目存在，null 抛 BizException（遵循 AGENTS.md） */
    private PromptLibraryItem requireItem(Long id) {
        if (id == null || id <= 0) {
            throw new BizException("id 非法");
        }
        PromptLibraryItem item = promptLibraryItemMapper.selectById(id);
        if (item == null) {
            throw new BizException("提示词库条目不存在: " + id);
        }
        return item;
    }

    @Override
    public List<StyleBrief> listStyles() {
        return styleMapper.selectList(
                        new LambdaQueryWrapper<Style>().orderByDesc(Style::getId))
                .stream()
                .map(s -> new StyleBrief(s.getId(), s.getName(), s.getVertical()))
                .toList();
    }

    /** 批量查风格名（避免 N+1） */
    private Map<Long, String> loadStyleNames(Set<Long> styleIds) {
        if (styleIds.isEmpty()) {
            return Map.of();
        }
        return styleMapper.selectList(
                        new LambdaQueryWrapper<Style>().in(Style::getId, styleIds))
                .stream().collect(Collectors.toMap(Style::getId, Style::getName, (a, b) -> a));
    }

    /** 库条目实体 -> 响应 DTO */
    private PromptLibraryItemResponse toItemResponse(PromptLibraryItem item, String styleName) {
        return new PromptLibraryItemResponse(
                item.getId(),
                item.getStyleId(),
                styleName,
                item.getContent(),
                item.getVertical(),
                item.getNote(),
                fromTagsJson(item.getTags()),
                item.getSourcePromptId(),
                item.getUsageCount() != null ? item.getUsageCount() : 0,
                item.getCreatedAt()
        );
    }

    /** 标签 List -> JSON 字符串；空列表返回 null */
    private String toTagsJson(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (Exception e) {
            log.warn("序列化 tags 失败: {}", tags, e);
            return null;
        }
    }

    /** JSON 字符串 -> 标签 List；空返回 null */
    private List<String> fromTagsJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("反序列化 tags 失败: {}", json, e);
            return null;
        }
    }
}
