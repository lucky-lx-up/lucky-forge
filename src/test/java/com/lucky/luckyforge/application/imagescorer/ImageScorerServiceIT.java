package com.lucky.luckyforge.application.imagescorer;

import com.lucky.luckyforge.application.imagescorer.dto.ScoreResult;
import com.lucky.luckyforge.application.imagescorer.dto.ScoreSummary;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.infrastructure.chatgpt.ChatGpt2ApiClient;
import com.lucky.luckyforge.infrastructure.persistence.entity.*;
import com.lucky.luckyforge.infrastructure.persistence.mapper.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ImageScorerService} 集成测试（mock chatgpt2api）。
 * <p>验证 service 逻辑：打分流程、TopN 标识、覆盖式、失败容错。
 *
 * <p>注意：service 在虚拟线程里写库（事务独立于测试主线程），故本测试通过
 * service 返回值断言核心逻辑，DB 落库由真实联调测试（ImageScorerLiveIT）验证。
 * 因此不用 @Transactional（虚拟线程事务不可见，回滚也无意义），改用唯一 runId 隔离。
 *
 * <p>并发=1，让 mock 的顺序返回值生效。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"image.concurrent-limit=1"})
class ImageScorerServiceIT {

    @Autowired private ImageScorerService imageScorerService;
    @Autowired private BatchMapper batchMapper;
    @Autowired private RunMapper runMapper;
    @Autowired private PromptMapper promptMapper;
    @Autowired private GeneratedImageMapper generatedImageMapper;

    @MockBean private ChatGpt2ApiClient chatGpt2ApiClient;

    @Test
    void 正常打分_topN标识正确() {
        Long[] ids = setupRunWithImages(3, 2); // 3 图，targetCount=2
        Long runId = ids[1];

        when(chatGpt2ApiClient.chatCompletion(anyString(), anyList()))
                .thenReturn(scoreJson(80))
                .thenReturn(scoreJson(95))
                .thenReturn(scoreJson(70));

        ScoreSummary summary = imageScorerService.scoreImages(runId);

        assertEquals(3, summary.total());
        assertEquals(3, summary.succeeded());
        assertEquals(2, summary.topN());

        // 按 total 降序：95 第一且 topN，80 第二且 topN，70 第三不 topN
        List<ScoreResult> results = summary.results();
        assertEquals(0, new BigDecimal("95").compareTo(results.get(0).total()));
        assertTrue(results.get(0).topN());
        assertTrue(results.get(1).topN());
        assertFalse(results.get(2).topN());
        // 成功的都有 scoreId
        results.forEach(r -> {
            assertTrue(r.success());
            assertNotNull(r.scoreId());
            assertEquals(4, r.dimensions().size());
        });
    }

    @Test
    void run无生成图时拒绝() {
        Batch batch = newBatch(2);
        batchMapper.insert(batch);
        Run run = newRun(batch.getId());
        runMapper.insert(run);

        BizException ex = assertThrows(BizException.class,
                () -> imageScorerService.scoreImages(run.getId()));
        assertTrue(ex.getMessage().contains("无生成图"));
    }

    @Test
    void 部分失败_成功的图保留() {
        Long[] ids = setupRunWithImages(2, 2);
        Long runId = ids[1];

        // 第一张抛异常，第二张成功（并发=1，顺序确定）
        when(chatGpt2ApiClient.chatCompletion(anyString(), anyList()))
                .thenThrow(new com.lucky.luckyforge.common.exception.ChatGptApiException(
                        "打分失败", 500, "err", null))
                .thenReturn(scoreJson(90));

        ScoreSummary summary = imageScorerService.scoreImages(runId);

        assertEquals(2, summary.total());
        assertEquals(1, summary.succeeded());
        assertEquals(1, summary.failed());
        // run.currentStep=SCORE
        Run run = runMapper.selectById(runId);
        assertEquals("SCORE", run.getCurrentStep());
    }

    /** 构造打分 JSON（含 4 维度，总分用 total） */
    private String scoreJson(int total) {
        return "{\"total\":" + total + ",\"remark\":\"test\",\"dimensions\":[" +
                "{\"name\":\"composition\",\"value\":" + total + "}," +
                "{\"name\":\"color\",\"value\":" + total + "}," +
                "{\"name\":\"clarity\",\"value\":" + total + "}," +
                "{\"name\":\"relevance\",\"value\":" + total + "}]}";
    }

    private Long[] setupRunWithImages(int imageCount, int targetCount) {
        Batch batch = newBatch(targetCount);
        batchMapper.insert(batch);
        Run run = newRun(batch.getId());
        runMapper.insert(run);
        Prompt prompt = new Prompt();
        prompt.setRunId(run.getId());
        prompt.setSeq(1);
        prompt.setContent("test prompt " + System.nanoTime());
        promptMapper.insert(prompt);
        for (int i = 0; i < imageCount; i++) {
            GeneratedImage gi = new GeneratedImage();
            gi.setPromptId(prompt.getId());
            gi.setObjectKey("test/scorer/" + System.nanoTime() + "_" + i + ".png");
            gi.setWidth(1024);
            gi.setHeight(1792);
            generatedImageMapper.insert(gi);
        }
        return new Long[]{batch.getId(), run.getId()};
    }

    private Batch newBatch(int targetCount) {
        Batch b = new Batch();
        b.setVertical("WALLPAPER");
        b.setTargetCount(targetCount);
        b.setStatus("DRAFT");
        b.setTheme("test");
        return b;
    }

    private Run newRun(Long batchId) {
        Run r = new Run();
        r.setBatchId(batchId);
        r.setStatus("RUNNING");
        r.setCurrentStep("GENERATE");
        return r;
    }
}
