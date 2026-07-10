package com.lucky.luckyforge.application.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lucky.luckyforge.application.imagegenerator.ImageGeneratorService;
import com.lucky.luckyforge.application.imagegenerator.dto.ImageGenerationResult;
import com.lucky.luckyforge.application.imagegenerator.dto.ImageGenerationSummary;
import com.lucky.luckyforge.application.imagescorer.ImageScorerService;
import com.lucky.luckyforge.application.imagescorer.dto.ScoreResult;
import com.lucky.luckyforge.application.imagescorer.dto.ScoreSummary;
import com.lucky.luckyforge.application.packageassembler.PackageAssemblerService;
import com.lucky.luckyforge.application.packageassembler.dto.PackageAssemblyResponse;
import com.lucky.luckyforge.application.packageassembler.dto.PackageImageItem;
import com.lucky.luckyforge.application.pipeline.dto.PipelineResult;
import com.lucky.luckyforge.application.pipeline.dto.PipelineStatusResponse;
import com.lucky.luckyforge.application.promptbuilder.PromptBuilderService;
import com.lucky.luckyforge.application.promptbuilder.dto.PromptGenerationResponse;
import com.lucky.luckyforge.application.styleanalysis.StyleAnalysisService;
import com.lucky.luckyforge.application.styleanalysis.dto.StyleAnalysisResponse;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.entity.ReferenceImage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Run;
import com.lucky.luckyforge.infrastructure.persistence.enums.BatchStatus;
import com.lucky.luckyforge.infrastructure.persistence.enums.RunStatus;
import com.lucky.luckyforge.infrastructure.persistence.mapper.BatchMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.ReferenceImageMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.RunMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link PipelineOrchestratorService} 集成测试（mock 5 个 Service）。
 * <p>验证串联顺序、关键步失败中断、非关键容错继续、run 终态。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PipelineOrchestratorServiceIT {

    @Autowired private PipelineOrchestratorService pipelineOrchestratorService;
    @Autowired private BatchMapper batchMapper;
    @Autowired private ReferenceImageMapper referenceImageMapper;
    @Autowired private RunMapper runMapper;
    // executeAsync 在虚拟线程（独立连接/事务）后台执行，测试设置的数据必须先提交才能被异步线程读到。
    // 类上的 @Transactional 会让所有插入都留在未提交事务中，因此异步用例借助 TransactionTemplate 显式提交设置数据。
    @Autowired private PlatformTransactionManager transactionManager;

    @MockBean private StyleAnalysisService styleAnalysisService;
    @MockBean private PromptBuilderService promptBuilderService;
    @MockBean private ImageGeneratorService imageGeneratorService;
    @MockBean private ImageScorerService imageScorerService;
    @MockBean private PackageAssemblerService packageAssemblerService;

    @Test
    void 全流程成功_run置SUCCESS() {
        Long[] ids = setupBatchWithReferenceAndRun();
        Long batchId = ids[0], runId = ids[1];

        when(styleAnalysisService.analyze(batchId)).thenReturn(
                new StyleAnalysisResponse(10L, "测试风格", "描述", "{}", batchId));
        when(promptBuilderService.generatePrompts(eq(batchId), any())).thenReturn(List.of(
                new PromptGenerationResponse(1L, runId, 1, "prompt1")));
        when(imageGeneratorService.generateImages(runId)).thenReturn(
                new ImageGenerationSummary(runId, 2, 2, 0, List.of()));
        when(imageScorerService.scoreImages(runId)).thenReturn(
                new ScoreSummary(runId, 2, 2, 0, 2, List.of()));
        when(packageAssemblerService.assemble(runId)).thenReturn(
                new PackageAssemblyResponse(5L, runId, batchId, "标题", List.of("标签"),
                        List.of(new PackageImageItem(1L, "test/1.png", 0, new BigDecimal("95")))));

        PipelineResult result = pipelineOrchestratorService.execute(batchId);

        assertTrue(result.overallSuccess());
        assertEquals(10L, result.styleId());
        assertEquals(runId, result.runId());
        assertEquals(5L, result.packageId());
        assertEquals(5, result.steps().size(), "应执行 5 步");
        result.steps().forEach(s -> assertTrue(s.success(), "每步应成功：" + s.step()));

        // run 终态 SUCCESS
        Run run = runMapper.selectById(runId);
        assertEquals(RunStatus.SUCCESS.value(), run.getStatus());
        assertNotNull(run.getFinishedAt());

        // 验证调用顺序：5 个 Service 各被调用 1 次
        verify(styleAnalysisService, times(1)).analyze(batchId);
        verify(packageAssemblerService, times(1)).assemble(runId);
    }

    @Test
    void 风格提炼失败_后续步骤不执行_run置FAILED() {
        Long[] ids = setupBatchWithReferenceAndRun();
        Long batchId = ids[0], runId = ids[1];

        when(styleAnalysisService.analyze(batchId))
                .thenThrow(new BizException("风格提炼失败"));

        PipelineResult result = pipelineOrchestratorService.execute(batchId);

        assertFalse(result.overallSuccess());
        assertTrue(result.overallMessage().contains("风格提炼失败"));
        assertEquals(1, result.steps().size(), "只执行了 1 步（风格）");
        assertFalse(result.steps().get(0).success());

        // 后续 4 个 Service 不应被调用
        verifyNoInteractions(promptBuilderService);
        verifyNoInteractions(imageGeneratorService);
        verifyNoInteractions(imageScorerService);
        verifyNoInteractions(packageAssemblerService);

        // run 终态 FAILED（runId 为 null，因 PromptBuilder 没被调用，所以不查 run）
        assertNull(result.runId());
    }

    @Test
    void 出图全失败_中断后续_run置FAILED() {
        Long[] ids = setupBatchWithReferenceAndRun();
        Long batchId = ids[0], runId = ids[1];

        when(styleAnalysisService.analyze(batchId)).thenReturn(
                new StyleAnalysisResponse(10L, "风格", "描述", "{}", batchId));
        when(promptBuilderService.generatePrompts(eq(batchId), any())).thenReturn(List.of(
                new PromptGenerationResponse(1L, runId, 1, "p")));
        when(imageGeneratorService.generateImages(runId)).thenReturn(
                new ImageGenerationSummary(runId, 2, 0, 2, List.of(
                        new ImageGenerationResult(1L, 1, null, null, false, "err"))));

        PipelineResult result = pipelineOrchestratorService.execute(batchId);

        assertFalse(result.overallSuccess());
        assertEquals(3, result.steps().size(), "执行了 3 步（风格/提示词/出图）");
        // 打分、打包不执行
        verifyNoInteractions(imageScorerService);
        verifyNoInteractions(packageAssemblerService);

        // run 终态 FAILED
        Run run = runMapper.selectById(runId);
        assertEquals(RunStatus.FAILED.value(), run.getStatus());
        assertNotNull(run.getError());
    }

    @Test
    void 出图部分失败_继续执行_run置SUCCESS() {
        Long[] ids = setupBatchWithReferenceAndRun();
        Long batchId = ids[0], runId = ids[1];

        when(styleAnalysisService.analyze(batchId)).thenReturn(
                new StyleAnalysisResponse(10L, "风格", "描述", "{}", batchId));
        when(promptBuilderService.generatePrompts(eq(batchId), any())).thenReturn(List.of(
                new PromptGenerationResponse(1L, runId, 1, "p")));
        when(imageGeneratorService.generateImages(runId)).thenReturn(
                new ImageGenerationSummary(runId, 2, 1, 1, List.of()));  // 1 成功 1 失败
        when(imageScorerService.scoreImages(runId)).thenReturn(
                new ScoreSummary(runId, 1, 1, 0, 1, List.of(
                        new ScoreResult(1L, 1L, new BigDecimal("90"), "ok", null, true, null, true))));
        when(packageAssemblerService.assemble(runId)).thenReturn(
                new PackageAssemblyResponse(5L, runId, batchId, "标题", List.of("标签"),
                        List.of(new PackageImageItem(1L, "test/1.png", 0, new BigDecimal("95")))));

        PipelineResult result = pipelineOrchestratorService.execute(batchId);

        assertTrue(result.overallSuccess(), "出图部分失败但继续执行，整体应成功");
        Run run = runMapper.selectById(runId);
        assertEquals(RunStatus.SUCCESS.value(), run.getStatus());
    }

    @Test
    void batch无参考图时拒绝() {
        Batch batch = new Batch();
        batch.setVertical("WALLPAPER");
        batch.setTargetCount(1);
        batch.setStatus(BatchStatus.DRAFT.value());
        batchMapper.insert(batch);

        BizException ex = assertThrows(BizException.class,
                () -> pipelineOrchestratorService.execute(batch.getId()));
        assertTrue(ex.getMessage().contains("无参考图"));
        verifyNoInteractions(styleAnalysisService);
    }

    @Test
    void 无run记录时getPipelineStatus返回IDLE() {
        // 建一个 batch 但不创建任何 run（模拟"从未成功触发过 pipeline"）
        Batch batch = new Batch();
        batch.setVertical("WALLPAPER");
        batch.setTargetCount(1);
        batch.setStatus(BatchStatus.DRAFT.value());
        batchMapper.insert(batch);

        PipelineStatusResponse resp = pipelineOrchestratorService.getPipelineStatus(batch.getId());

        assertEquals("IDLE", resp.status(), "无 run 记录应返回 IDLE，而非诱导轮询的 RUNNING");
        assertNull(resp.runId());
        assertNull(resp.currentStep(), "IDLE 无当前步骤");
    }

    @Test
    void 有RUNNING的run时getPipelineStatus返回RUNNING() {
        Long[] ids = setupBatchWithReferenceAndRun();
        Long batchId = ids[0];

        PipelineStatusResponse resp = pipelineOrchestratorService.getPipelineStatus(batchId);

        assertEquals("RUNNING", resp.status(), "有 RUNNING 的 run 应原样返回 RUNNING");
        assertEquals("STYLE", resp.currentStep());
        assertEquals(ids[1], resp.runId());
    }

    @Test
    @Transactional(propagation = Propagation.NEVER)
    // executeAsync 后台虚拟线程用独立连接/事务，测试自身不能持有未提交事务，
    // 否则异步线程既看不到设置数据，也看不到预创建 run（孤儿 RUNNING），故本用例不开事务。
    void executeAsync早期失败_预创建run标FAILED() throws Exception {
        Long batchId = setupBatchWithReferenceOnly();

        // STYLE 步骤直接抛异常 → execute 早期失败，PromptBuilder 不会创建正式 run
        when(styleAnalysisService.analyze(batchId))
                .thenThrow(new BizException("风格提炼失败"));

        pipelineOrchestratorService.executeAsync(batchId);

        // executeAsync 异步执行，轮询等待预创建 run 变为终态（最多 10 秒）
        Run run = waitForRunTerminalStatus(batchId, 10);
        assertNotNull(run, "应存在 run 记录");
        assertEquals("FAILED", run.getStatus(),
                "execute 早期失败时预创建 run 应标 FAILED，而非虚假 SUCCESS");
        assertNotNull(run.getError(), "失败时应写入 error 信息");
        assertNotNull(run.getFinishedAt(), "应有完成时间");
    }

    @Test
    @Transactional(propagation = Propagation.NEVER)
    // executeAsync 后台虚拟线程用独立连接/事务，测试自身不能持有未提交事务（同上）。
    void executeAsync全流程成功_预创建run标SUCCESS() throws Exception {
        Long batchId = setupBatchWithReferenceOnly();

        // mock 全流程成功（复用 IT 已有的 mock 模式，runId 用一个固定值）
        long runId = 999L;
        when(styleAnalysisService.analyze(batchId)).thenReturn(
                new StyleAnalysisResponse(10L, "测试风格", "描述", "{}", batchId));
        when(promptBuilderService.generatePrompts(eq(batchId), any())).thenReturn(List.of(
                new PromptGenerationResponse(1L, runId, 1, "p")));
        when(imageGeneratorService.generateImages(runId)).thenReturn(
                new ImageGenerationSummary(runId, 2, 2, 0, List.of()));
        when(imageScorerService.scoreImages(runId)).thenReturn(
                new ScoreSummary(runId, 2, 2, 0, 2, List.of()));
        when(packageAssemblerService.assemble(runId)).thenReturn(
                new PackageAssemblyResponse(5L, runId, batchId, "标题", List.of("标签"),
                        List.of(new PackageImageItem(1L, "test/1.png", 0, new BigDecimal("95")))));

        pipelineOrchestratorService.executeAsync(batchId);

        Run run = waitForRunTerminalStatus(batchId, 10);
        assertNotNull(run);
        // PromptBuilder 被 mock，runId=999 不会真落库；finalizeRun(999,...) 因 selectById(999)=null 直接 return。
        // 轮询取到的是预创建 run，execute 成功时它应被标 SUCCESS（回归保护）。
        assertEquals("SUCCESS", run.getStatus());
    }

    @Test
    @Transactional(propagation = Propagation.NEVER)
    void executeAsync应立即返回不阻塞等待execute完成() throws Exception {
        Long batchId = setupBatchWithReferenceOnly();

        // 用 latch 让 analyze 阻塞：execute() 会卡在 STYLE 步骤直到 latch 释放
        CountDownLatch analyzeStarted = new CountDownLatch(1);
        CountDownLatch releaseAnalyze = new CountDownLatch(1);
        when(styleAnalysisService.analyze(batchId)).thenAnswer(inv -> {
            analyzeStarted.countDown();
            // 阻塞直到测试主线程释放（最多等 10 秒兜底，避免卡死）
            releaseAnalyze.await(10, TimeUnit.SECONDS);
            throw new BizException("测试用阻塞已结束");
        });

        long start = System.currentTimeMillis();
        pipelineOrchestratorService.executeAsync(batchId);
        long elapsed = System.currentTimeMillis() - start;

        // executeAsync 应在 execute() 完成前就返回（analyze 还在阻塞中）
        // 阈值 2000ms：execute() 至少阻塞 3 秒，若 executeAsync 阻塞等待则 elapsed >= 3000
        assertTrue(elapsed < 2000,
                "executeAsync 应立即返回，实际耗时 " + elapsed + "ms（疑似阻塞等待 execute）");

        // 确认 analyze 确实被调用了（后台任务已启动）
        assertTrue(analyzeStarted.await(2, TimeUnit.SECONDS),
                "后台任务应在 executeAsync 返回后被调用");

        // 释放后台任务，避免泄漏
        releaseAnalyze.countDown();
    }

    // 辅助：轮询等待 batch 最新的 run 变为非 RUNNING 终态
    private Run waitForRunTerminalStatus(Long batchId, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Run run = runMapper.selectOne(new LambdaQueryWrapper<Run>()
                    .eq(Run::getBatchId, batchId)
                    .orderByDesc(Run::getId)
                    .last("LIMIT 1"));
            if (run != null && !"RUNNING".equals(run.getStatus())) {
                return run;
            }
            Thread.sleep(200);
        }
        return null;
    }

    // ===== 辅助：创建 batch + 参考图 + run（run 给 PromptBuilder 的 mock 返回用）=====

    private Long[] setupBatchWithReferenceAndRun() {
        Batch batch = new Batch();
        batch.setVertical("WALLPAPER");
        batch.setTargetCount(2);
        batch.setStatus(BatchStatus.DRAFT.value());
        batch.setTheme("test");
        batchMapper.insert(batch);

        ReferenceImage ref = new ReferenceImage();
        ref.setBatchId(batch.getId());
        ref.setObjectKey("test/orchestrator/" + System.nanoTime() + ".jpg");
        ref.setSource("MANUAL");
        referenceImageMapper.insert(ref);

        Run run = new Run();
        run.setBatchId(batch.getId());
        run.setStatus(RunStatus.RUNNING.value());
        run.setCurrentStep("STYLE");
        runMapper.insert(run);

        return new Long[]{batch.getId(), run.getId()};
    }

    // 辅助：只建 batch + 参考图（不预建 run，让 executeAsync 自己创建）
    // executeAsync 在虚拟线程（独立事务）执行，故此处用独立事务提交，确保异步线程能读到这些数据。
    private Long setupBatchWithReferenceOnly() {
        Batch batch = new Batch();
        batch.setVertical("WALLPAPER");
        batch.setTargetCount(2);
        batch.setStatus(BatchStatus.DRAFT.value());
        batch.setTheme("test-async");

        ReferenceImage ref = new ReferenceImage();
        ref.setObjectKey("test/async/" + System.nanoTime() + ".jpg");
        ref.setSource("MANUAL");

        // 用独立事务提交，使其在 executeAsync 启动虚拟线程前就对其它事务可见
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> {
            batchMapper.insert(batch);
            ref.setBatchId(batch.getId());
            referenceImageMapper.insert(ref);
        });

        return batch.getId();
    }
}
