package com.lucky.luckyforge.application.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lucky.luckyforge.application.imagescorer.ImageScorerService;
import com.lucky.luckyforge.application.imagescorer.dto.ScoreSummary;
import com.lucky.luckyforge.application.imagegenerator.ImageGeneratorService;
import com.lucky.luckyforge.application.imagegenerator.dto.ImageGenerationSummary;
import com.lucky.luckyforge.application.packageassembler.PackageAssemblerService;
import com.lucky.luckyforge.application.packageassembler.dto.PackageAssemblyResponse;
import com.lucky.luckyforge.application.pipeline.dto.PipelineResult;
import com.lucky.luckyforge.application.pipeline.dto.PipelineStepResult;
import com.lucky.luckyforge.application.pipeline.dto.PipelineStatusResponse;
import com.lucky.luckyforge.application.promptbuilder.PromptBuilderService;
import com.lucky.luckyforge.application.promptbuilder.dto.PromptGenerationRequest;
import com.lucky.luckyforge.application.promptbuilder.dto.PromptGenerationResponse;
import com.lucky.luckyforge.application.styleanalysis.StyleAnalysisService;
import com.lucky.luckyforge.application.styleanalysis.dto.StyleAnalysisResponse;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.infrastructure.persistence.entity.ReferenceImage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Run;
import com.lucky.luckyforge.infrastructure.persistence.enums.RunStatus;
import com.lucky.luckyforge.infrastructure.persistence.mapper.BatchMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.ReferenceImageMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.RunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 流水线编排服务实现。
 * <p>复用 5 个单环 Service，串联执行：风格提炼→提示词→出图→打分→打包。
 *
 * <p>关键步失败中断（风格/提示词/出图全失败）；非关键容错继续（出图部分失败/打分部分失败/打包 TopN 不足）。
 * run.status 终值由本服务统一置定（SUCCESS 全程无致命失败 / FAILED 致命失败）。
 *
 * <p>不引入新事务（各环节自有事务策略，orchestrator 避免长事务锁定）。
 */
@Service
public class PipelineOrchestratorServiceImpl implements PipelineOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestratorServiceImpl.class);

    /** run 在 RUNNING 状态超过此分钟数，视为异常中断（如后端重启杀线程），允许重跑 */
    private static final int RUN_TIMEOUT_MINUTES = 10;

    @Autowired private StyleAnalysisService styleAnalysisService;
    @Autowired private PromptBuilderService promptBuilderService;
    @Autowired private ImageGeneratorService imageGeneratorService;
    @Autowired private ImageScorerService imageScorerService;
    @Autowired private PackageAssemblerService packageAssemblerService;
    @Autowired private BatchMapper batchMapper;
    @Autowired private ReferenceImageMapper referenceImageMapper;
    @Autowired private RunMapper runMapper;

    @Override
    public PipelineResult execute(Long batchId) {
        return execute(batchId, null);
    }

    private PipelineResult execute(Long batchId, Integer count) {
        // 1. 校验 batch 存在
        if (batchId == null || batchId <= 0) {
            throw new BizException("batchId 非法");
        }
        if (batchMapper.selectById(batchId) == null) {
            throw new BizException("批次不存在: " + batchId);
        }
        // 校验有参考图
        long refCount = referenceImageMapper.selectCount(
                new LambdaQueryWrapper<ReferenceImage>().eq(ReferenceImage::getBatchId, batchId));
        if (refCount == 0) {
            throw new BizException("批次无参考图: " + batchId);
        }

        List<PipelineStepResult> steps = new ArrayList<>(5);
        Long runId = null;
        Long styleId = null;
        Long packageId = null;
        String failureMessage = null;

        try {
            // 步骤①：风格提炼（关键步）
            StyleAnalysisResponse style = executeStep("STYLE", steps, () ->
                    styleAnalysisService.analyze(batchId));
            styleId = style.styleId();

            // 步骤②：提示词生成（关键步）
            List<PromptGenerationResponse> prompts = executeStep("PROMPT", steps, () ->
                    promptBuilderService.generatePrompts(batchId, count != null ? new PromptGenerationRequest(count) : null));
            if (prompts == null || prompts.isEmpty()) {
                throw new BizException("提示词生成结果为空");
            }
            runId = prompts.get(0).runId();
            final Long runIdFinal = runId;

            // 步骤③：出图（关键步：全失败才中断）
            ImageGenerationSummary imgSummary = executeStep("GENERATE", steps, () ->
                    imageGeneratorService.generateImages(runIdFinal));
            if (imgSummary.succeeded() == 0) {
                throw new BizException("出图全失败（成功 0/" + imgSummary.total() + "），无法继续");
            }

            // 步骤④：打分（非关键：部分失败继续）
            ScoreSummary scoreSummary = executeStep("SCORE", steps, () ->
                    imageScorerService.scoreImages(runIdFinal));
            if (scoreSummary.succeeded() == 0) {
                throw new BizException("打分全失败（成功 0/" + scoreSummary.total() + "），无法打包");
            }

            // 步骤⑤：打包（关键步）
            PackageAssemblyResponse pkg = executeStep("PACKAGE", steps, () ->
                    count != null
                            ? packageAssemblerService.assemble(runIdFinal, count)
                            : packageAssemblerService.assemble(runIdFinal));
            packageId = pkg.packageId();

            // 全流程成功 → run.status=SUCCESS
            finalizeRun(runId, RunStatus.SUCCESS, null);
            return new PipelineResult(batchId, runId, styleId, packageId,
                    true, "全流程成功", steps);

        } catch (PipelineStepFailureException | BizException e) {
            failureMessage = e.getMessage();
            log.warn("流水线中断 batchId={}: {}", batchId, failureMessage);
            finalizeRun(runId, RunStatus.FAILED, failureMessage);
            return new PipelineResult(batchId, runId, styleId, packageId,
                    false, "流水线中断: " + failureMessage, steps);
        }
    }

    /**
     * 执行单步并记录结果。失败时包装为 PipelineStepFailureException 抛出（中断）。
     *
     * @param stepName 步骤名
     * @param steps    累积的步骤结果列表
     * @param action   步骤动作（返回关键产出对象）
     * @param <T>      产出类型
     * @return 步骤产出
     * @throws PipelineStepFailureException 步骤失败时
     */
    private <T> T executeStep(String stepName, List<PipelineStepResult> steps,
                              StepAction<T> action) throws PipelineStepFailureException {
        long start = System.currentTimeMillis();
        try {
            T result = action.run();
            long elapsed = System.currentTimeMillis() - start;
            steps.add(new PipelineStepResult(stepName, true, elapsed, describe(result), null));
            return result;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            steps.add(new PipelineStepResult(stepName, false, elapsed, null, errMsg));
            // BizException 直接传播（保留业务语义），其他包装
            if (e instanceof BizException) {
                throw new PipelineStepFailureException(errMsg);
            }
            throw new PipelineStepFailureException(stepName + " 步骤异常: " + errMsg);
        }
    }

    /** 描述步骤产出（用于 detail 字段） */
    private String describe(Object result) {
        if (result == null) return "null";
        if (result instanceof StyleAnalysisResponse s) return "styleId=" + s.styleId() + ", name=" + s.name();
        if (result instanceof List<?> list) return "count=" + list.size();
        if (result instanceof ImageGenerationSummary s)
            return "succeeded=" + s.succeeded() + "/" + s.total();
        if (result instanceof ScoreSummary s)
            return "succeeded=" + s.succeeded() + "/" + s.total() + ", topN=" + s.topN();
        if (result instanceof PackageAssemblyResponse p)
            return "packageId=" + p.packageId() + ", images=" + p.images().size();
        return result.toString();
    }

    @Override
    public Long executeAsync(Long batchId) {
        return executeAsync(batchId, null);
    }

    @Override
    public Long executeAsync(Long batchId, Integer count) {
        // 校验（与 execute 同步前置检查）
        if (batchId == null || batchId <= 0) {
            throw new BizException("batchId 非法");
        }
        if (batchMapper.selectById(batchId) == null) {
            throw new BizException("批次不存在: " + batchId);
        }
        long refCount = referenceImageMapper.selectCount(
                new LambdaQueryWrapper<ReferenceImage>().eq(ReferenceImage::getBatchId, batchId));
        if (refCount == 0) {
            throw new BizException("批次无参考图: " + batchId);
        }

        // 检查是否已有 pipeline 在跑（查该 batch 最新的 run 是否 RUNNING）
        Run latestRun = findLatestRun(batchId);
        if (latestRun != null && "RUNNING".equals(latestRun.getStatus())) {
            if (isRunTimedOut(latestRun)) {
                // 超时（如后端重启杀线程），标记 FAILED，允许重跑
                log.warn("run {} 在 RUNNING 状态超时（>{}分钟），标记为 FAILED 允许重跑",
                        latestRun.getId(), RUN_TIMEOUT_MINUTES);
                latestRun.setStatus("FAILED");
                latestRun.setFinishedAt(LocalDateTime.now());
                latestRun.setError("执行超时（>" + RUN_TIMEOUT_MINUTES + "分钟未完成），疑似中断");
                runMapper.updateById(latestRun);
            } else {
                throw new BizException("该批次已有流水线在执行中，请等待完成（或 " + RUN_TIMEOUT_MINUTES
                        + " 分钟后自动超时可重跑）");
            }
        }

        // 预创建 run（RUNNING/STYLE），让 getPipelineStatus 立即能查到"正在跑"
        // PromptBuilder 后续会创建正式 run（id 更大），findLatestRun 返回正式的，此预创建的会被"盖过"
        Run preRun = new Run();
        preRun.setBatchId(batchId);
        preRun.setStatus("RUNNING");
        preRun.setCurrentStep("STYLE");
        preRun.setStartedAt(LocalDateTime.now());
        runMapper.insert(preRun);

        // 虚拟线程后台执行（fire-and-forget：提交后立即返回，进度通过 lf_run 表追踪，重启不丢）
        final Long preRunId = preRun.getId();
        Thread.startVirtualThread(() -> {
            boolean success = false;
            String failureMsg = null;
            try {
                PipelineResult result = execute(batchId, count);
                success = result.overallSuccess();
                if (!success) {
                    failureMsg = result.overallMessage();
                }
            } catch (Exception e) {
                failureMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.error("异步 pipeline 执行异常 batchId={}", batchId, e);
            } finally {
                // 按 execute 真实结果标记预创建 run 终态（避免孤儿 RUNNING）
                Run pre = runMapper.selectById(preRunId);
                if (pre != null && "RUNNING".equals(pre.getStatus())) {
                    if (success) {
                        pre.setStatus("SUCCESS");
                    } else {
                        pre.setStatus("FAILED");
                        pre.setError(failureMsg != null ? failureMsg : "流水线执行失败");
                    }
                    pre.setFinishedAt(LocalDateTime.now());
                    runMapper.updateById(pre);
                }
            }
        });

        return batchId;
    }

    @Override
    public PipelineStatusResponse getPipelineStatus(Long batchId) {
        // 查 lf_run 表（持久化，重启不丢）
        Run run = findLatestRun(batchId);
        if (run == null) {
            // 无 run 记录 = 从未成功触发过 pipeline（executeAsync 成功后会预创建 run）。
            // 返回 IDLE 而非 RUNNING，避免前端 checkRunningPipeline 误判为"执行中"而永久轮询。
            return new PipelineStatusResponse(null, "IDLE", null,
                    "无流水线执行记录", null, List.of());
        }
        // 映射 run 状态为前端展示
        String status = run.getStatus(); // PENDING/RUNNING/SUCCESS/FAILED
        String currentStep = "SUCCESS".equals(status) || "FAILED".equals(status) ? null : run.getCurrentStep();
        return new PipelineStatusResponse(run.getId(), status, currentStep,
                run.getError(), null, List.of());
    }

    /** 查 batch 最新的 run 记录（按 id 倒序） */
    private Run findLatestRun(Long batchId) {
        return runMapper.selectOne(new LambdaQueryWrapper<Run>()
                .eq(Run::getBatchId, batchId)
                .orderByDesc(Run::getId)
                .last("LIMIT 1"));
    }

    /** 判断 run 是否超时（startedAt 至今超过 RUN_TIMEOUT_MINUTES 分钟） */
    private boolean isRunTimedOut(Run run) {
        if (run.getStartedAt() == null) {
            // 没有 startedAt（理论上不会，PromptBuilder 创建时设了），用 createdAt 兜底
            if (run.getCreatedAt() == null) return true;
            return run.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(RUN_TIMEOUT_MINUTES));
        }
        return run.getStartedAt().isBefore(LocalDateTime.now().minusMinutes(RUN_TIMEOUT_MINUTES));
    }

    /**
     * 定时清理：每 2 分钟扫描所有 RUNNING 状态的 run，超时的自动标记 FAILED。
     * <p>无需前端触发，后端自动处理卡死的 run（如后端重启后遗留的 RUNNING）。
     */
    @Scheduled(fixedDelay = 120000) // 每 2 分钟
    public void cleanupTimedOutRuns() {
        List<Run> runningRuns = runMapper.selectList(
                new LambdaQueryWrapper<Run>().eq(Run::getStatus, "RUNNING"));
        for (Run run : runningRuns) {
            if (isRunTimedOut(run)) {
                log.warn("定时清理：run {} 在 RUNNING 状态超时（>{}分钟），自动标记 FAILED",
                        run.getId(), RUN_TIMEOUT_MINUTES);
                run.setStatus("FAILED");
                run.setFinishedAt(LocalDateTime.now());
                run.setError("执行超时（>" + RUN_TIMEOUT_MINUTES + "分钟未完成），自动清理");
                runMapper.updateById(run);
            }
        }
    }

    /** 定 run 终态 */
    private void finalizeRun(Long runId, RunStatus status, String error) {
        if (runId == null) return;
        Run run = runMapper.selectById(runId);
        if (run == null) return;
        run.setStatus(status.value());
        run.setFinishedAt(LocalDateTime.now());
        if (error != null) {
            run.setError(error);
        }
        runMapper.updateById(run);
    }

    /** 步骤动作函数式接口 */
    @FunctionalInterface
    private interface StepAction<T> {
        T run() throws Exception;
    }

    /** 步骤失败异常（内部用，控制中断流） */
    private static class PipelineStepFailureException extends Exception {
        PipelineStepFailureException(String message) {
            super(message);
        }
    }
}
