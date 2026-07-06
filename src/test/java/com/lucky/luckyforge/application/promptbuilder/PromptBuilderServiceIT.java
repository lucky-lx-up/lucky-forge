package com.lucky.luckyforge.application.promptbuilder;

import com.lucky.luckyforge.application.promptbuilder.dto.PromptGenerationRequest;
import com.lucky.luckyforge.application.promptbuilder.dto.PromptGenerationResponse;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.infrastructure.chatgpt.ChatGpt2ApiClient;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.entity.Prompt;
import com.lucky.luckyforge.infrastructure.persistence.entity.Run;
import com.lucky.luckyforge.infrastructure.persistence.entity.Style;
import com.lucky.luckyforge.infrastructure.persistence.enums.BatchStatus;
import com.lucky.luckyforge.infrastructure.persistence.enums.RunStatus;
import com.lucky.luckyforge.infrastructure.persistence.enums.Vertical;
import com.lucky.luckyforge.infrastructure.persistence.mapper.BatchMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.PromptMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.RunMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.StyleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link PromptBuilderService} 集成测试（mock chatgpt2api，连真实 MySQL）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PromptBuilderServiceIT {

    @Autowired private PromptBuilderService promptBuilderService;
    @Autowired private BatchMapper batchMapper;
    @Autowired private StyleMapper styleMapper;
    @Autowired private RunMapper runMapper;
    @Autowired private PromptMapper promptMapper;

    @MockBean private ChatGpt2ApiClient chatGpt2ApiClient;

    @Test
    void 正常生成提示词_单次调用产N条() {
        // 准备 batch + style
        Style style = newStyle();
        styleMapper.insert(style);
        Batch batch = newBatch(style.getId());
        batchMapper.insert(batch);

        // mock gpt-5.5 返回 3 条提示词的 JSON 数组
        String gptResp = """
                [
                  {"seq":1,"content":"minimalist sunset over mountain, warm gradient"},
                  {"seq":2,"content":"minimalist ocean horizon, cool gradient"},
                  {"seq":3,"content":"minimalist forest silhouette, green gradient"}
                ]
                """;
        when(chatGpt2ApiClient.chatCompletion(anyString(), anyString())).thenReturn(gptResp);

        List<PromptGenerationResponse> result =
                promptBuilderService.generatePrompts(batch.getId(), new PromptGenerationRequest(3));

        // 断言
        assertEquals(3, result.size());
        // gpt 仅被调用 1 次（单次调用产 N 条）
        verify(chatGpt2ApiClient, times(1)).chatCompletion(anyString(), anyString());

        // lf_prompt 落库
        for (PromptGenerationResponse r : result) {
            Prompt p = promptMapper.selectById(r.id());
            assertNotNull(p);
            assertEquals(r.runId(), p.getRunId());
            assertEquals(r.seq(), p.getSeq());
            assertTrue(p.getContent().contains("minimalist"));
        }

        // run 创建且状态正确
        Long runId = result.get(0).runId();
        Run run = runMapper.selectById(runId);
        assertNotNull(run);
        assertEquals(RunStatus.RUNNING.value(), run.getStatus());
        assertEquals("PROMPT", run.getCurrentStep());
        assertNotNull(run.getStartedAt());

        // 提示词是英文
        assertTrue(result.get(0).content().matches("^[\\x00-\\x7F]+$"),
                "提示词应为纯英文");
    }

    @Test
    void batch无styleId时拒绝() {
        Batch batch = newBatch(null); // styleId = null
        batchMapper.insert(batch);

        BizException ex = assertThrows(BizException.class,
                () -> promptBuilderService.generatePrompts(batch.getId(), null));
        assertTrue(ex.getMessage().contains("风格"));
    }

    @Test
    void count超限时拒绝() {
        Style style = newStyle();
        styleMapper.insert(style);
        Batch batch = newBatch(style.getId());
        batchMapper.insert(batch);

        assertThrows(IllegalArgumentException.class,
                () -> promptBuilderService.generatePrompts(batch.getId(),
                        new PromptGenerationRequest(15)));
    }

    @Test
    void gpt返回非JSON数组时标记run失败() {
        Style style = newStyle();
        styleMapper.insert(style);
        Batch batch = newBatch(style.getId());
        batchMapper.insert(batch);

        when(chatGpt2ApiClient.chatCompletion(anyString(), anyString())).thenReturn("这不是JSON");

        BizException ex = assertThrows(BizException.class,
                () -> promptBuilderService.generatePrompts(batch.getId(),
                        new PromptGenerationRequest(2)));
        assertTrue(ex.getMessage().contains("解析"));

        // run 被标记 FAILED
        List<Run> runs = runMapper.selectList(null);
        Run lastRun = runs.get(runs.size() - 1);
        assertEquals(RunStatus.FAILED.value(), lastRun.getStatus());
        assertNotNull(lastRun.getFinishedAt());
    }

    @Test
    void 默认count用batchTargetCount() {
        Style style = newStyle();
        styleMapper.insert(style);
        Batch batch = newBatch(style.getId());
        batch.setTargetCount(2);
        batchMapper.insert(batch);

        when(chatGpt2ApiClient.chatCompletion(anyString(), anyString())).thenReturn(
                "[{\"seq\":1,\"content\":\"a\"},{\"seq\":2,\"content\":\"b\"}]");

        List<PromptGenerationResponse> result =
                promptBuilderService.generatePrompts(batch.getId(), null);
        assertEquals(2, result.size());
    }

    private Style newStyle() {
        Style s = new Style();
        s.setName("测试风格-" + System.nanoTime());
        s.setVertical(Vertical.WALLPAPER.value());
        s.setDescription("暖色调极简");
        s.setStyleJson("{\"palette\":\"warm\"}");
        return s;
    }

    private Batch newBatch(Long styleId) {
        Batch b = new Batch();
        b.setVertical(Vertical.WALLPAPER.value());
        b.setTargetCount(4);
        b.setStatus(BatchStatus.DRAFT.value());
        b.setStyleId(styleId);
        b.setTheme("测试主题");
        return b;
    }
}
