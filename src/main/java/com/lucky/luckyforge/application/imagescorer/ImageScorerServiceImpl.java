package com.lucky.luckyforge.application.imagescorer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.luckyforge.application.imagescorer.dto.DimensionScore;
import com.lucky.luckyforge.application.imagescorer.dto.ScoreResult;
import com.lucky.luckyforge.application.imagescorer.dto.ScoreSummary;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.infrastructure.chatgpt.ChatGpt2ApiClient;
import com.lucky.luckyforge.infrastructure.chatgpt.dto.ContentPart;
import com.lucky.luckyforge.infrastructure.chatgpt.dto.MultimodalMessage;
import com.lucky.luckyforge.infrastructure.persistence.entity.*;
import com.lucky.luckyforge.infrastructure.persistence.mapper.*;
import com.lucky.luckyforge.infrastructure.storage.MinioStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * 自动打分服务实现（流水线第④环）。
 * <p>流程：校验 run → 查 run 下所有生成图（通过 prompt 关联）→ 虚拟线程并发打分
 * → 覆盖式写 lf_score + lf_score_dimension → 按 total 降序标识 Top N → 汇总。
 *
 * <p>并发：复用 ImageGenerator 的虚拟线程 + Semaphore 模式。
 * 覆盖式：先删该图既有 dimension + score，再插新（@Transactional 保护单图写库）。
 * Top N：按 total 降序，前 batch.targetCount 个标 topN=true，不删除非 Top 图。
 */
@Service
public class ImageScorerServiceImpl implements ImageScorerService {

    private static final Logger log = LoggerFactory.getLogger(ImageScorerServiceImpl.class);

    /** 系统提示词：约束 gpt-5.5 按 4 维度打分，返回严格 JSON */
    private static final String SYSTEM_PROMPT = """
            你是手机壁纸质量评审员。对给定的壁纸图按以下 4 个维度打分（每项 0-100 分）：
            - composition（构图）
            - color（色彩）
            - clarity（清晰度）
            - relevance（主题契合度）
            同时给出总分（0-100，可含 1 位小数）与简短评语。
            严格按以下 JSON 格式返回（不要任何额外文字、不要 markdown 代码块包裹）：
            {"total":85.5,"remark":"评语","dimensions":[{"name":"composition","value":88},{"name":"color","value":90},{"name":"clarity","value":82},{"name":"relevance","value":85}]}
            """;

    private static final String USER_INSTRUCTION = "请评审这张手机壁纸图的质量。";

    private final int concurrentLimit;

    @Autowired private RunMapper runMapper;
    @Autowired private BatchMapper batchMapper;
    @Autowired private PromptMapper promptMapper;
    @Autowired private GeneratedImageMapper generatedImageMapper;
    @Autowired private ScoreMapper scoreMapper;
    @Autowired private ScoreDimensionMapper scoreDimensionMapper;
    @Autowired private ChatGpt2ApiClient chatGpt2ApiClient;
    @Autowired private MinioStorageService storageService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionTemplate transactionTemplate;

    @Autowired
    public ImageScorerServiceImpl(
            @Value("${image.concurrent-limit:4}") int concurrentLimit) {
        this.concurrentLimit = Math.max(1, concurrentLimit);
    }

    @Override
    public ScoreSummary scoreImages(Long runId) {
        // 1. 校验 run
        if (runId == null || runId <= 0) {
            throw new BizException("runId 非法");
        }
        Run run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException("运行不存在: " + runId);
        }

        // 2. 查 run 下所有生成图（通过 prompt.runId 关联）
        List<GeneratedImage> images = findRunImages(runId);
        if (images.isEmpty()) {
            throw new BizException("运行无生成图: " + runId);
        }

        // 3. 查 batch 决定 TopN 阈值
        Batch batch = batchMapper.selectById(run.getBatchId());
        int topN = (batch != null && batch.getTargetCount() != null && batch.getTargetCount() > 0)
                ? batch.getTargetCount() : images.size();

        // 4. 更新 run.currentStep = SCORE
        run.setCurrentStep("SCORE");
        runMapper.updateById(run);

        // 5. 虚拟线程并发打分
        List<ScoreResult> results = concurrentScore(images);

        // 6. 标识 Top N（按 total 降序，成功的才参与排名）
        markTopN(results, topN);

        // 7. 汇总 + 失败记 run.error（不改 status）
        int succeeded = (int) results.stream().filter(ScoreResult::success).count();
        int failed = results.size() - succeeded;
        if (failed > 0) {
            run.setError(buildErrorMessage(results));
            runMapper.updateById(run);
        }

        return new ScoreSummary(runId, results.size(), succeeded, failed, topN, results);
    }

    /**
     * 查 run 下所有生成图：先查 run 的 prompt id 列表，再按 promptId 查 generated_image。
     * 避免 N+1，用 in 批量查。
     */
    private List<GeneratedImage> findRunImages(Long runId) {
        List<Prompt> prompts = promptMapper.selectList(
                new LambdaQueryWrapper<Prompt>().eq(Prompt::getRunId, runId));
        if (prompts.isEmpty()) {
            return List.of();
        }
        List<Long> promptIds = prompts.stream().map(Prompt::getId).toList();
        return generatedImageMapper.selectList(
                new LambdaQueryWrapper<GeneratedImage>().in(GeneratedImage::getPromptId, promptIds));
    }

    /** 虚拟线程 + Semaphore 并发打分 */
    private List<ScoreResult> concurrentScore(List<GeneratedImage> images) {
        Semaphore semaphore = new Semaphore(concurrentLimit);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ScoreResult>> futures = new ArrayList<>(images.size());
            for (GeneratedImage img : images) {
                futures.add(executor.submit(() -> scoreOne(img, semaphore)));
            }
            List<ScoreResult> results = new ArrayList<>(futures.size());
            for (Future<ScoreResult> f : futures) {
                try {
                    results.add(f.get());
                } catch (Exception e) {
                    log.error("获取打分结果异常", e);
                    results.add(new ScoreResult(null, null, null, null, null, false, e.getMessage(), false));
                }
            }
            return results;
        }
    }

    /**
     * 单张图打分：acquire semaphore → 生成预签名 URL → 多模态调 gpt-5.5 → 解析 → 覆盖式写库。
     */
    private ScoreResult scoreOne(GeneratedImage image, Semaphore semaphore) {
        try {
            semaphore.acquire();
            try {
                // 生成预签名 URL
                String url = storageService.getPublicUrl(image.getObjectKey());
                // 组装多模态消息
                List<MultimodalMessage> messages = List.of(MultimodalMessage.user(List.of(
                        ContentPart.ofText(USER_INSTRUCTION),
                        ContentPart.ofImage(url)
                )));
                // 调 gpt-5.5
                String raw = chatGpt2ApiClient.chatCompletion(SYSTEM_PROMPT, messages);
                // 解析
                ParsedScore parsed = parseScore(raw);
                // 覆盖式写库
                Long scoreId = writeScoreTransactional(
                        image.getId(), parsed.total, parsed.remark, parsed.dimensions);

                return new ScoreResult(image.getId(), scoreId, parsed.total, parsed.remark,
                        parsed.dimensions, true, null, false);
            } finally {
                semaphore.release();
            }
        } catch (BizException e) {
            log.warn("打分失败（解析）generatedImageId={}: {}", image.getId(), e.getMessage());
            return fail(image, e.getMessage());
        } catch (com.lucky.luckyforge.common.exception.ChatGptApiException e) {
            log.warn("打分失败（gpt）generatedImageId={}: {}", image.getId(), e.getMessage());
            return fail(image, "gpt 打分失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("打分失败 generatedImageId={}", image.getId(), e);
            return fail(image, "打分失败: " + e.getMessage());
        }
    }

    /**
     * 覆盖式写库（事务保护）：先删既有 dimension + score，再插新。
     * <p>用编程式事务（TransactionTemplate）——因为本方法从虚拟线程内被自调用，
     * @Transactional 注解的 AOP 代理在自调用场景失效，编程式事务是 Spring 官方推荐方案。
     * 符合 AGENTS.md「多次连续数据库执行用 @Transactional 或 transactionTemplate」。
     */
    private Long writeScoreTransactional(Long generatedImageId, BigDecimal total,
                                         String remark, List<DimensionScore> dimensions) {
        return transactionTemplate.execute(status -> {
            // 1. 查既有 score（覆盖式）
            Score existing = scoreMapper.selectOne(
                    new LambdaQueryWrapper<Score>().eq(Score::getGeneratedImageId, generatedImageId));
            if (existing != null) {
                // 先删 dimension（按 scoreId），再删 score（顺序反了会残留孤儿 dimension）
                scoreDimensionMapper.delete(new LambdaQueryWrapper<ScoreDimension>()
                        .eq(ScoreDimension::getScoreId, existing.getId()));
                scoreMapper.deleteById(existing.getId());
            }
            // 2. 插新 score
            Score score = new Score();
            score.setGeneratedImageId(generatedImageId);
            score.setTotal(total);
            score.setRemark(remark);
            scoreMapper.insert(score);
            // 3. 插 dimension
            for (DimensionScore d : dimensions) {
                ScoreDimension sd = new ScoreDimension();
                sd.setScoreId(score.getId());
                sd.setName(d.name());
                sd.setValue(d.value());
                scoreDimensionMapper.insert(sd);
            }
            return score.getId();
        });
    }

    /** 解析 gpt-5.5 返回的打分 JSON，容错处理 */
    private ParsedScore parseScore(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException("gpt-5.5 未返回有效内容");
        }
        String json = stripCodeFence(raw).trim();
        try {
            JsonNode root = objectMapper.readTree(json);
            BigDecimal total = root.has("total") && !root.get("total").isNull()
                    ? root.get("total").decimalValue() : null;
            String remark = root.has("remark") && !root.get("remark").isNull()
                    ? root.get("remark").asText() : null;
            List<DimensionScore> dims = new ArrayList<>();
            if (root.has("dimensions") && root.get("dimensions").isArray()) {
                for (JsonNode d : root.get("dimensions")) {
                    String name = d.has("name") ? d.get("name").asText() : null;
                    BigDecimal value = d.has("value") && !d.get("value").isNull()
                            ? d.get("value").decimalValue() : null;
                    if (name == null || value == null) {
                        throw new BizException("维度缺少 name 或 value");
                    }
                    dims.add(new DimensionScore(name, value));
                }
            }
            if (dims.isEmpty()) {
                throw new BizException("gpt-5.5 未返回维度分");
            }
            if (total == null) {
                throw new BizException("gpt-5.5 未返回总分");
            }
            return new ParsedScore(total, remark, dims);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析打分 JSON 失败: {}", json, e);
            throw new BizException("解析打分 JSON 失败: " + e.getMessage());
        }
    }

    /** 剥离 markdown 代码块 */
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

    /** 标识 Top N：按 total 降序，前 topN 个成功的标 topN=true */
    private void markTopN(List<ScoreResult> results, int topN) {
        // 排序：成功的按 total 降序在前，失败的排末尾
        List<ScoreResult> sorted = results.stream()
                .sorted(Comparator
                        .comparing((ScoreResult r) -> !r.success()) // false(成功) 排前
                        .thenComparing(r -> r.total() == null ? BigDecimal.ZERO : r.total(),
                                Comparator.reverseOrder())) // total 降序
                .collect(Collectors.toList());
        // 标 Top N
        int rank = 0;
        for (int i = 0; i < sorted.size(); i++) {
            ScoreResult r = sorted.get(i);
            boolean isTop = r.success() && rank < topN;
            sorted.set(i, new ScoreResult(r.generatedImageId(), r.scoreId(), r.total(),
                    r.remark(), r.dimensions(), r.success(), r.errorMessage(), isTop));
            if (r.success()) rank++;
        }
        results.clear();
        results.addAll(sorted);
    }

    private ScoreResult fail(GeneratedImage image, String error) {
        return new ScoreResult(image.getId(), null, null, null, null, false, error, false);
    }

    private String buildErrorMessage(List<ScoreResult> results) {
        StringBuilder sb = new StringBuilder("部分打分失败:");
        for (ScoreResult r : results) {
            if (!r.success()) {
                sb.append("\n  - generatedImageId=").append(r.generatedImageId())
                        .append(": ").append(r.errorMessage());
            }
        }
        return sb.toString();
    }

    /** 内部解析结果 */
    private record ParsedScore(BigDecimal total, String remark, List<DimensionScore> dimensions) {
    }
}
