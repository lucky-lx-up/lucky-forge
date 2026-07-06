package com.lucky.luckyforge.application.promptbuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.luckyforge.application.promptbuilder.dto.PromptGenerationRequest;
import com.lucky.luckyforge.application.promptbuilder.dto.PromptGenerationResponse;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.infrastructure.chatgpt.ChatGpt2ApiClient;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.entity.Prompt;
import com.lucky.luckyforge.infrastructure.persistence.entity.Run;
import com.lucky.luckyforge.infrastructure.persistence.entity.Style;
import com.lucky.luckyforge.infrastructure.persistence.enums.RunStatus;
import com.lucky.luckyforge.infrastructure.persistence.mapper.BatchMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.PromptMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.RunMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.StyleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 提示词生成服务实现（流水线第②环）。
 * <p>流程：校验 batch + styleId → 创建 Run(RUNNING/PROMPT) → 调 gpt-5.5 单次生成 N 条
 * → 解析 JSON 数组 → 逐条写 lf_prompt → 返回。
 *
 * <p>关键设计：单次 chatCompletion 调用产 N 条（让模型看到全局差异化约束），省 N-1 次调用。
 * 提示词强制英文（gpt-image-2 对英文响应更好）。
 */
@Service
public class PromptBuilderServiceImpl implements PromptBuilderService {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilderServiceImpl.class);

    /**
     * 系统提示词：约束 gpt-5.5 生成 N 条主题各异、风格统一的英文提示词，返回 JSON 数组。
     */
    private static final String SYSTEM_PROMPT = """
            你是一名 AI 绘画提示词工程师。基于给定的风格特征与主题，生成指定数量的出图提示词。
            要求：
            1. 所有提示词 MUST 用英文撰写（gpt-image-2 对英文响应更好）。
            2. 各提示词的主题/场景 MUST 不同（主题各异），但风格特征（色调/构图/氛围）MUST 统一。
            3. 每条提示词应是一段完整的描述，含主体、风格、构图、氛围，适合直接送 gpt-image-2。
            4. 严格按以下 JSON 数组格式返回（不要任何额外文字、不要 markdown 代码块包裹）：
            [{"seq":1,"content":"<英文提示词1>"},{"seq":2,"content":"<英文提示词2>"},...]
            seq 从 1 递增。
            """;

    @Autowired private BatchMapper batchMapper;
    @Autowired private StyleMapper styleMapper;
    @Autowired private RunMapper runMapper;
    @Autowired private PromptMapper promptMapper;
    @Autowired private ChatGpt2ApiClient chatGpt2ApiClient;
    @Autowired private ObjectMapper objectMapper;

    @Override
    @Transactional
    public List<PromptGenerationResponse> generatePrompts(Long batchId, PromptGenerationRequest request) {
        // 1. 校验 batch
        if (batchId == null || batchId <= 0) {
            throw new BizException("batchId 非法");
        }
        Batch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BizException("批次不存在: " + batchId);
        }
        if (batch.getStyleId() == null) {
            throw new BizException("批次尚未提炼风格，请先执行风格提炼");
        }

        // 2. 解析 count
        int count = resolveCount(request, batch);

        // 3. 查风格
        Style style = styleMapper.selectById(batch.getStyleId());
        if (style == null) {
            throw new BizException("风格记录不存在: " + batch.getStyleId());
        }

        // 4. 创建 Run（status=RUNNING, currentStep=PROMPT）
        Run run = new Run();
        run.setBatchId(batchId);
        run.setStatus(RunStatus.RUNNING.value());
        run.setCurrentStep("PROMPT");
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);

        // 5. 调 gpt-5.5 单次生成 N 条
        String userMessage = buildUserMessage(style, batch.getTheme(), count);
        String rawResponse;
        try {
            rawResponse = chatGpt2ApiClient.chatCompletion(SYSTEM_PROMPT, userMessage);
        } catch (RuntimeException ex) {
            markRunFailed(run, "调用 gpt-5.5 失败: " + ex.getMessage());
            throw ex;
        }

        // 6. 解析 JSON 数组
        List<ParsedPrompt> parsed;
        try {
            parsed = parsePrompts(rawResponse, count);
        } catch (BizException ex) {
            markRunFailed(run, ex.getMessage());
            throw ex;
        }

        // 7. 逐条写 lf_prompt
        List<PromptGenerationResponse> results = new ArrayList<>(parsed.size());
        for (ParsedPrompt p : parsed) {
            Prompt prompt = new Prompt();
            prompt.setRunId(run.getId());
            prompt.setSeq(p.seq);
            prompt.setContent(p.content);
            promptMapper.insert(prompt);
            results.add(new PromptGenerationResponse(
                    prompt.getId(), run.getId(), p.seq, p.content));
        }

        return results;
    }

    /** 解析 count：request 非空用 request，否则 batch.targetCount，仍无则默认 4 */
    private int resolveCount(PromptGenerationRequest request, Batch batch) {
        if (request != null && request.count() != null) {
            return request.count();
        }
        if (batch.getTargetCount() != null && batch.getTargetCount() > 0) {
            int target = batch.getTargetCount();
            if (target > PromptGenerationRequest.MAX_COUNT) {
                throw new BizException("batch.targetCount 超过上限 " + PromptGenerationRequest.MAX_COUNT
                        + "，请在请求中显式指定较小的 count");
            }
            return target;
        }
        return 4;
    }

    /** 组装用户消息：风格描述 + 结构化特征 + 主题 + 数量 */
    private String buildUserMessage(Style style, String theme, int count) {
        StringBuilder sb = new StringBuilder();
        sb.append("风格名称：").append(style.getName()).append("\n");
        sb.append("风格描述：").append(style.getDescription() != null ? style.getDescription() : "（无）").append("\n");
        sb.append("结构化特征（JSON）：").append(style.getStyleJson() != null ? style.getStyleJson() : "（无）").append("\n");
        sb.append("本次主题/意图：").append(theme != null && !theme.isBlank() ? theme : "（自由发挥）").append("\n");
        sb.append("\n请基于以上风格，生成 ").append(count).append(" 条主题各异、风格统一的英文出图提示词。");
        return sb.toString();
    }

    /** 解析 gpt-5.5 返回的 JSON 数组，容错处理 */
    private List<ParsedPrompt> parsePrompts(String raw, int expectedCount) {
        if (raw == null || raw.isBlank()) {
            throw new BizException("gpt-5.5 未返回有效内容");
        }
        String json = stripCodeFence(raw).trim();
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                throw new BizException("gpt-5.5 返回非 JSON 数组");
            }
            List<ParsedPrompt> result = new ArrayList<>(root.size());
            for (JsonNode item : root) {
                int seq = item.has("seq") ? item.get("seq").asInt() : result.size() + 1;
                String content = item.has("content") ? item.get("content").asText() : null;
                if (content == null || content.isBlank()) {
                    throw new BizException("gpt-5.5 返回的提示词 content 为空");
                }
                result.add(new ParsedPrompt(seq, content));
            }
            if (result.isEmpty()) {
                throw new BizException("gpt-5.5 返回空数组");
            }
            if (result.size() < expectedCount) {
                log.warn("期望 {} 条提示词，实际返回 {} 条", expectedCount, result.size());
            }
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析提示词 JSON 失败: {}", json, e);
            throw new BizException("解析提示词 JSON 失败: " + e.getMessage());
        }
    }

    /** 剥离可能的 markdown ```json ... ``` 包裹 */
    private String stripCodeFence(String s) {
        String trimmed = s.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }

    /** 标记 run 失败 */
    private void markRunFailed(Run run, String error) {
        run.setStatus(RunStatus.FAILED.value());
        run.setError(error);
        run.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(run);
    }

    /** 内部解析结果 */
    private record ParsedPrompt(int seq, String content) {
    }
}
