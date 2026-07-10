package com.lucky.luckyforge.application.packageassembler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.luckyforge.application.packageassembler.dto.PackageAssemblyResponse;
import com.lucky.luckyforge.application.packageassembler.dto.PackageImageItem;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.infrastructure.chatgpt.ChatGpt2ApiClient;
import com.lucky.luckyforge.infrastructure.chatgpt.dto.ContentPart;
import com.lucky.luckyforge.infrastructure.chatgpt.dto.MultimodalMessage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.entity.GeneratedImage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Package;
import com.lucky.luckyforge.infrastructure.persistence.entity.PackageImage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Prompt;
import com.lucky.luckyforge.infrastructure.persistence.entity.Run;
import com.lucky.luckyforge.infrastructure.persistence.entity.Score;
import com.lucky.luckyforge.infrastructure.persistence.entity.Style;
import com.lucky.luckyforge.infrastructure.persistence.mapper.*;
import com.lucky.luckyforge.infrastructure.storage.MinioStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 素材打包服务实现（流水线第⑤环）。
 * <p>流程：校验 run → 查 run 下所有 score（按 total 降序取 Top N）→ 多模态调 gpt-5.5 生成标题/标签
 * → 累积式写 lf_package + lf_package_image → 推进 run.currentStep=PACKAGE。
 *
 * <p>关键设计：一个 run 一个包；标题看 Top N 图生成（≤5 张）；sort_order 按 total 降序（最高分封面）；
 * 累积式（每次打包追加新 package，历史保留，多次执行可查看全部历史产出）。
 *
 * <p>注意：本服务的写库逻辑在**主线程**执行（非虚拟线程，与 ImageScorer/ImageGenerator 不同），
 * 因为打包是单次 gpt 调用 + 单次写库，无需并发。故用 @Transactional 即可（无自调用问题）。
 */
@Service
public class PackageAssemblerServiceImpl implements PackageAssemblerService {

    private static final Logger log = LoggerFactory.getLogger(PackageAssemblerServiceImpl.class);

    /** 标题生成时最多看 5 张图（防止多模态 token 超限） */
    private static final int MAX_IMAGES_FOR_TITLE = 5;

    /** 系统提示词：约束 gpt-5.5 生成中文标题+标签，返回严格 JSON */
    private static final String SYSTEM_PROMPT = """
            你是手机壁纸文案作者。基于给定的壁纸图与风格特征，生成 1 个吸引人的标题和 3-5 个标签。
            要求：
            1. 标题用中文，8-16 字，生动有画面感。
            2. 标签 3-5 个中文词，覆盖风格、主题、情绪。
            3. 严格按以下 JSON 格式返回（不要任何额外文字、不要 markdown 代码块包裹）：
            {"title":"标题","tags":["标签1","标签2","标签3"]}
            """;

    @Autowired private RunMapper runMapper;
    @Autowired private BatchMapper batchMapper;
    @Autowired private StyleMapper styleMapper;
    @Autowired private PromptMapper promptMapper;
    @Autowired private ScoreMapper scoreMapper;
    @Autowired private GeneratedImageMapper generatedImageMapper;
    @Autowired private PackageMapper packageMapper;
    @Autowired private PackageImageMapper packageImageMapper;
    @Autowired private ChatGpt2ApiClient chatGpt2ApiClient;
    @Autowired private MinioStorageService storageService;
    @Autowired private ObjectMapper objectMapper;

    @Override
    @Transactional
    public PackageAssemblyResponse assemble(Long runId) {
        return assemble(runId, null);
    }

    @Override
    @Transactional
    public PackageAssemblyResponse assemble(Long runId, Integer count) {
        // 1. 校验 run
        if (runId == null || runId <= 0) {
            throw new BizException("runId 非法");
        }
        Run run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException("运行不存在: " + runId);
        }

        // 2. 查 run 下所有 score（通过生成图关联），按 total 降序
        List<Score> scores = findRunScores(runId);
        if (scores.isEmpty()) {
            throw new BizException("运行无打分结果: " + runId);
        }
        // 按 total 降序
        scores.sort(Comparator.comparing(
                (Score s) -> s.getTotal() == null ? BigDecimal.ZERO : s.getTotal(),
                Comparator.reverseOrder()));

        // 3. 查 batch 决定 TopN + vertical
        Batch batch = batchMapper.selectById(run.getBatchId());
        int topN = (count != null && count > 0)
                ? count
                : (batch != null && batch.getTargetCount() != null && batch.getTargetCount() > 0
                        ? batch.getTargetCount() : scores.size());
        // 取 Top N（不足则用全部）
        List<Score> topScores = scores.subList(0, Math.min(topN, scores.size()));

        // 4. 查这些 score 对应的生成图
        List<Long> imageIds = topScores.stream().map(Score::getGeneratedImageId).toList();
        List<GeneratedImage> topImages = generatedImageMapper.selectList(
                new LambdaQueryWrapper<GeneratedImage>().in(GeneratedImage::getId, imageIds));
        if (topImages.isEmpty()) {
            throw new BizException("Top N 图查询为空");
        }

        // 5. 多模态调 gpt-5.5 生成标题/标签（看前 MAX 张图）
        String vertical = batch != null ? batch.getVertical() : "WALLPAPER";
        Style style = batch != null && batch.getStyleId() != null
                ? styleMapper.selectById(batch.getStyleId()) : null;
        GeneratedTitle generated = generateTitle(topImages, style);

        // 6. 累积式写库（@Transactional 保护）：直接追加新 package，历史保留
        Package pkg = new Package();
        pkg.setBatchId(run.getBatchId());
        pkg.setVertical(vertical);
        pkg.setTitle(generated.title);
        pkg.setTags(generated.tagsJson); // JSON 数组字符串
        pkg.setStatus("DRAFT");
        packageMapper.insert(pkg);
        // 6.2 插 package_image（按 topImages 在 topScores 中的 total 降序 sort_order）
        // 构建 imageId -> score 映射
        java.util.Map<Long, BigDecimal> imgScore = topScores.stream()
                .collect(Collectors.toMap(Score::getGeneratedImageId,
                        s -> s.getTotal() == null ? BigDecimal.ZERO : s.getTotal(),
                        (a, b) -> a));
        // topImages 按 score 降序排（与 topScores 顺序一致）
        topImages.sort(Comparator.comparing(
                (GeneratedImage g) -> imgScore.getOrDefault(g.getId(), BigDecimal.ZERO),
                Comparator.reverseOrder()));
        List<PackageImageItem> imageItems = new ArrayList<>(topImages.size());
        for (int i = 0; i < topImages.size(); i++) {
            GeneratedImage gi = topImages.get(i);
            PackageImage pi = new PackageImage();
            pi.setPackageId(pkg.getId());
            pi.setGeneratedImageId(gi.getId());
            pi.setSortOrder(i);
            packageImageMapper.insert(pi);
            imageItems.add(new PackageImageItem(gi.getId(), gi.getObjectKey(), i,
                    imgScore.get(gi.getId())));
        }

        // 7. 推进 run.currentStep=PACKAGE（不改 status）
        run.setCurrentStep("PACKAGE");
        runMapper.updateById(run);

        return new PackageAssemblyResponse(pkg.getId(), runId, run.getBatchId(),
                generated.title, generated.tags, imageItems);
    }

    /**
     * 查 run 下所有 score：通过 prompt.runId → generatedImage → score 关联。
     */
    private List<Score> findRunScores(Long runId) {
        List<Prompt> runPrompts = promptMapper.selectList(
                new LambdaQueryWrapper<Prompt>().eq(Prompt::getRunId, runId));
        if (runPrompts.isEmpty()) {
            return List.of();
        }
        List<Long> promptIds = runPrompts.stream().map(Prompt::getId).toList();
        List<GeneratedImage> images = generatedImageMapper.selectList(
                new LambdaQueryWrapper<GeneratedImage>().in(GeneratedImage::getPromptId, promptIds));
        if (images.isEmpty()) {
            return List.of();
        }
        List<Long> imageIds = images.stream().map(GeneratedImage::getId).toList();
        return scoreMapper.selectList(
                new LambdaQueryWrapper<Score>().in(Score::getGeneratedImageId, imageIds));
    }

    /** 多模态调 gpt-5.5 生成标题+标签 */
    private GeneratedTitle generateTitle(List<GeneratedImage> images, Style style) {
        // 只看前 MAX 张图
        List<GeneratedImage> forTitle = images.subList(0, Math.min(MAX_IMAGES_FOR_TITLE, images.size()));

        List<ContentPart> parts = new ArrayList<>(forTitle.size() + 1);
        // 文字指令含风格描述
        StringBuilder instruction = new StringBuilder("请为这组壁纸生成标题和标签。");
        if (style != null) {
            instruction.append(" 风格名称：").append(style.getName());
            if (style.getDescription() != null) {
                instruction.append("。风格描述：").append(style.getDescription());
            }
        }
        parts.add(ContentPart.ofText(instruction.toString()));
        for (GeneratedImage gi : forTitle) {
            parts.add(ContentPart.ofImage(storageService.getPublicUrl(gi.getObjectKey())));
        }

        String raw = chatGpt2ApiClient.chatCompletion(SYSTEM_PROMPT,
                List.of(MultimodalMessage.user(parts)));
        return parseTitle(raw);
    }

    /** 解析标题 JSON，容错 */
    private GeneratedTitle parseTitle(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException("gpt-5.5 未返回有效内容");
        }
        String json = stripCodeFence(raw).trim();
        try {
            JsonNode root = objectMapper.readTree(json);
            String title = root.has("title") && !root.get("title").isNull()
                    ? root.get("title").asText() : null;
            if (title == null || title.isBlank()) {
                throw new BizException("gpt-5.5 未返回标题");
            }
            List<String> tags = new ArrayList<>();
            if (root.has("tags") && root.get("tags").isArray()) {
                for (JsonNode t : root.get("tags")) {
                    tags.add(t.asText());
                }
            }
            String tagsJson = objectMapper.writeValueAsString(tags);
            return new GeneratedTitle(title, tags, tagsJson);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析标题 JSON 失败: {}", json, e);
            throw new BizException("解析标题 JSON 失败: " + e.getMessage());
        }
    }

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

    /** 临时方法：返回 PromptMapper（避免类顶部字段未声明） */
    @Autowired private PromptMapper promptMapperField;
    private PromptMapper promptMapper() { return promptMapperField; }

    /** 内部生成结果 */
    private record GeneratedTitle(String title, List<String> tags, String tagsJson) {
    }
}
