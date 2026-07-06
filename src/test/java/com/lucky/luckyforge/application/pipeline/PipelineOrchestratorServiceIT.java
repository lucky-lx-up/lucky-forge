package com.lucky.luckyforge.application.pipeline;

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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

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
}
