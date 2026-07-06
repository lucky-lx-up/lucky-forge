package com.lucky.luckyforge.application.styleanalysis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.luckyforge.application.styleanalysis.dto.StyleAnalysisResponse;
import com.lucky.luckyforge.application.styleanalysis.dto.StyleFeatures;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.infrastructure.chatgpt.ChatGpt2ApiClient;
import com.lucky.luckyforge.infrastructure.chatgpt.dto.ContentPart;
import com.lucky.luckyforge.infrastructure.chatgpt.dto.MultimodalMessage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.entity.ReferenceImage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Style;
import com.lucky.luckyforge.infrastructure.persistence.mapper.BatchMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.ReferenceImageMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.StyleMapper;
import com.lucky.luckyforge.infrastructure.storage.MinioStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 风格提炼服务实现。
 * <p>流程：校验 batch → 查参考图 → 生成预签名 URL → 多模态调 gpt-5.5 → 解析风格 JSON
 * → 写 lf_style → 回填 batch.styleId。
 *
 * <p>系统提示词常量内聚于本类（YAGNI，不抽公共模板）。
 * 风格 JSON 解析容错：剥离 markdown 代码块、trim，失败抛 BizException。
 * 写库与回填在 {@code @Transactional} 内，保证一致性。
 */
@Service
public class StyleAnalysisServiceImpl implements StyleAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(StyleAnalysisServiceImpl.class);

    /** 单批次参考图上限（防止 token 超限与超时） */
    private static final int MAX_REFERENCE_IMAGES = 5;

    /**
     * 系统提示词：约束 gpt-5.5 输出严格 JSON（name/description/features）。
     */
    private static final String SYSTEM_PROMPT = """
            你是一名资深视觉风格分析师。请分析给定的参考图，提炼可复用的风格特征。
            严格按以下 JSON 格式返回（不要任何额外文字、不要 markdown 代码块包裹）：
            {
              "name": "<风格名称，4-12字>",
              "description": "<风格综述，自然语言描述色调/构图/主题/氛围，30-80字>",
              "features": {
                "palette": "<色调特征>",
                "composition": "<构图特征>",
                "subject": "<主题特征>",
                "mood": "<氛围特征>"
              }
            }
            """;

    /** 用户消息文字指令 */
    private static final String USER_INSTRUCTION =
            "请分析以下参考图，提炼它们的共同风格特征并按指定 JSON 格式返回。";

    @Autowired private BatchMapper batchMapper;
    @Autowired private ReferenceImageMapper referenceImageMapper;
    @Autowired private StyleMapper styleMapper;
    @Autowired private ChatGpt2ApiClient chatGpt2ApiClient;
    @Autowired private MinioStorageService storageService;
    @Autowired private ObjectMapper objectMapper;

    @Override
    @Transactional
    public StyleAnalysisResponse analyze(Long batchId) {
        // 1. 校验 batch
        if (batchId == null || batchId <= 0) {
            throw new BizException("batchId 非法");
        }
        Batch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BizException("批次不存在: " + batchId);
        }

        // 2. 查参考图
        List<ReferenceImage> refs = referenceImageMapper.selectList(
                new LambdaQueryWrapper<ReferenceImage>()
                        .eq(ReferenceImage::getBatchId, batchId));
        if (refs.isEmpty()) {
            throw new BizException("批次无参考图: " + batchId);
        }
        if (refs.size() > MAX_REFERENCE_IMAGES) {
            throw new BizException("参考图数量超限（最多 " + MAX_REFERENCE_IMAGES + " 张，当前 " + refs.size() + "）");
        }

        // 3. 组装多模态消息并调用 gpt-5.5
        List<MultimodalMessage> messages = buildMessages(refs);
        String rawResponse = chatGpt2ApiClient.chatCompletion(SYSTEM_PROMPT, messages);

        // 4. 解析风格 JSON
        AnalyzedStyle analyzed = parseStyle(rawResponse);

        // 5. 写 lf_style + 回填 batch.styleId（@Transactional 保护）
        Style style = new Style();
        style.setName(analyzed.name);
        style.setVertical(batch.getVertical());
        style.setDescription(analyzed.description);
        style.setStyleJson(analyzed.styleJson);
        styleMapper.insert(style);

        batch.setStyleId(style.getId());
        batchMapper.updateById(batch);

        return new StyleAnalysisResponse(
                style.getId(),
                analyzed.name,
                analyzed.description,
                analyzed.styleJson,
                batchId);
    }

    /**
     * 组装多模态消息：用户消息含文字指令 + 每张参考图的预签名 URL。
     */
    private List<MultimodalMessage> buildMessages(List<ReferenceImage> refs) {
        List<ContentPart> parts = new ArrayList<>(refs.size() + 1);
        parts.add(ContentPart.ofText(USER_INSTRUCTION));
        for (ReferenceImage ri : refs) {
            // 动态生成预签名 URL（1 小时有效），不持久化
            String url = storageService.getPublicUrl(ri.getObjectKey());
            parts.add(ContentPart.ofImage(url));
        }
        return List.of(MultimodalMessage.user(parts));
    }

    /**
     * 解析 gpt-5.5 返回的风格 JSON，容错处理（剥离 markdown 代码块、trim）。
     */
    private AnalyzedStyle parseStyle(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException("gpt-5.5 未返回有效内容");
        }
        String json = stripCodeFence(raw).trim();
        try {
            var node = objectMapper.readTree(json);
            String name = textOrNull(node, "name");
            String description = textOrNull(node, "description");
            String styleJson = null;
            if (node.has("features") && !node.get("features").isNull()) {
                // features 子对象原样序列化回字符串落库
                StyleFeatures features = objectMapper.treeToValue(node.get("features"), StyleFeatures.class);
                styleJson = objectMapper.writeValueAsString(features);
            }
            if (name == null || name.isBlank()) {
                throw new BizException("gpt-5.5 返回的 name 缺失");
            }
            return new AnalyzedStyle(name, description, styleJson);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析风格 JSON 失败: {}", json, e);
            throw new BizException("解析风格 JSON 失败: " + e.getMessage());
        }
    }

    /** 剥离可能的 markdown ```json ... ``` 代码块包裹 */
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

    /** 从 JsonNode 取字符串字段，缺失或 null 返回 null */
    private String textOrNull(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText();
        }
        return null;
    }

    /** 内部解析结果容器 */
    private record AnalyzedStyle(String name, String description, String styleJson) {
    }
}
