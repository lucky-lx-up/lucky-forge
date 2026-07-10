package com.lucky.luckyforge.application.imagegenerator;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lucky.luckyforge.application.imagegenerator.dto.ImageGenerationResult;
import com.lucky.luckyforge.application.imagegenerator.dto.ImageGenerationSummary;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.infrastructure.chatgpt.ChatGpt2ApiClient;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.entity.GeneratedImage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Prompt;
import com.lucky.luckyforge.infrastructure.persistence.entity.Run;
import com.lucky.luckyforge.infrastructure.persistence.mapper.BatchMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.GeneratedImageMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.PromptMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.RunMapper;
import com.lucky.luckyforge.infrastructure.storage.MinioStorageService;
import com.lucky.luckyforge.infrastructure.storage.ObjectKeyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * 出图服务实现（流水线第③环）。
 * <p>流程：校验 run → currentStep=GENERATE → 虚拟线程 + Semaphore 并发出图 → 汇总成败 → 更新 run 状态。
 *
 * <p>并发策略：Java 21 虚拟线程（{@code newVirtualThreadPerTaskExecutor}）承载每条 prompt 的
 * IO 等待（单张出图 30s）；{@link Semaphore} 限制实际并发为配置值（默认 4），避免触发
 * chatgpt2api 服务端限流。单张失败不阻断其他，已成功图保留。
 *
 * <p>尺寸策略：WALLPAPER → 1024x1792（竖屏，实测支持）；其余 → 1024x1024。
 */
@Service
public class ImageGeneratorServiceImpl implements ImageGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(ImageGeneratorServiceImpl.class);

    /** 壁纸垂类的出图尺寸（竖屏，实测 gpt-image-2 支持） */
    private static final String SIZE_WALLPAPER = "1024x1792";
    /** 其他垂类的默认出图尺寸（方图） */
    private static final String SIZE_DEFAULT = "1024x1024";
    /** 壁纸垂类名（与 Vertical 枚举对应） */
    private static final String VERTICAL_WALLPAPER = "WALLPAPER";
    /** 生成图扩展名 */
    private static final String IMAGE_EXT = "png";
    private static final String IMAGE_CONTENT_TYPE = "image/png";

    /** 并发限流（可配置，默认 4），用 final 字段配合构造期 @Value */
    private final int concurrentLimit;

    @Autowired private RunMapper runMapper;
    @Autowired private BatchMapper batchMapper;
    @Autowired private PromptMapper promptMapper;
    @Autowired private GeneratedImageMapper generatedImageMapper;
    @Autowired private ChatGpt2ApiClient chatGpt2ApiClient;
    @Autowired private MinioStorageService storageService;

    @Autowired
    public ImageGeneratorServiceImpl(
            @Value("${image.concurrent-limit:4}") int concurrentLimit) {
        this.concurrentLimit = Math.max(1, concurrentLimit);
    }

    @Override
    public ImageGenerationSummary generateImages(Long runId) {
        // 1. 校验 run
        if (runId == null || runId <= 0) {
            throw new BizException("runId 非法");
        }
        Run run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException("运行不存在: " + runId);
        }

        // 2. 查 batch 拿 vertical 决定 size
        Batch batch = batchMapper.selectById(run.getBatchId());
        if (batch == null) {
            throw new BizException("运行关联的批次不存在: " + run.getBatchId());
        }
        String size = resolveSize(batch.getVertical());

        // 3. 查 run 下所有 prompt
        List<Prompt> prompts = promptMapper.selectList(
                new LambdaQueryWrapper<Prompt>().eq(Prompt::getRunId, runId));
        if (prompts.isEmpty()) {
            throw new BizException("运行无提示词: " + runId);
        }

        // 4. 更新 run.currentStep = GENERATE
        run.setCurrentStep("GENERATE");
        runMapper.updateById(run);

        // 5. 虚拟线程并发出图
        List<ImageGenerationResult> results = concurrentGenerate(prompts, batch.getVertical(), size);

        // 6. 汇总结果返回（run 终态由 PipelineOrchestrator 统一管理，本步骤不越权设置）
        int succeeded = (int) results.stream().filter(ImageGenerationResult::success).count();
        int failed = results.size() - succeeded;

        return new ImageGenerationSummary(runId, results.size(), succeeded, failed, results);
    }

    /**
     * 虚拟线程 + Semaphore 并发出图。
     * 每条 prompt 一个虚拟线程任务，acquire semaphore 后调 gpt-image-2 + 上传 + 写库。
     */
    private List<ImageGenerationResult> concurrentGenerate(List<Prompt> prompts, String vertical, String size) {
        Semaphore semaphore = new Semaphore(concurrentLimit);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 提交所有任务
            List<Future<ImageGenerationResult>> futures = new ArrayList<>(prompts.size());
            for (Prompt p : prompts) {
                futures.add(executor.submit(() -> generateOne(p, vertical, size, semaphore)));
            }
            // 等待全部完成，按提交顺序收集结果
            List<ImageGenerationResult> results = new ArrayList<>(futures.size());
            for (Future<ImageGenerationResult> f : futures) {
                try {
                    results.add(f.get());
                } catch (Exception e) {
                    // 不应发生（generateOne 内部已捕获所有异常），兜底
                    log.error("获取出图结果异常", e);
                    results.add(new ImageGenerationResult(null, null, null, null, false, e.getMessage()));
                }
            }
            return results;
        }
    }

    /**
     * 单条 prompt 出图：acquire semaphore → gpt-image-2 → decode → MinIO → 写库。
     * 任何异常都捕获，返回带 errorMessage 的失败结果（不抛出，避免阻断整批）。
     *
     * <p>chatgpt2api 的出图响应偶发以 application/octet-stream 返回（服务端不稳定），
     * 导致 RestClient 反序列化失败。虽然 ChatGpt2ApiClient 已配置兜底 converter，
     * 但极端情况下仍可能失败。这里在业务层加一次重试（重新请求），覆盖这种偶发。
     */
    private ImageGenerationResult generateOne(Prompt prompt, String vertical, String size, Semaphore semaphore) {
        try {
            semaphore.acquire();
            try {
                return attemptGenerate(prompt, vertical, size);
            } finally {
                semaphore.release();
            }
        } catch (Exception e) {
            // 第一次失败，尝试重试一次（覆盖 octet-stream 等偶发）
            log.warn("出图首次失败 prompt={}: {}，重试中...", prompt.getId(), e.getMessage());
            try {
                semaphore.acquire();
                try {
                    ImageGenerationResult retry = attemptGenerate(prompt, vertical, size);
                    log.info("出图重试成功 prompt={}", prompt.getId());
                    return retry;
                } finally {
                    semaphore.release();
                }
            } catch (Exception retryEx) {
                log.error("出图重试仍失败 prompt={}: {}", prompt.getId(), retryEx.getMessage());
                return fail(prompt, "出图失败（重试后）: " + retryEx.getMessage());
            }
        }
    }

    /**
     * 实际执行一次出图：gpt-image-2 → decode → MinIO → 写库。
     * 失败时抛异常（由调用方决定是否重试）。
     */
    private ImageGenerationResult attemptGenerate(Prompt prompt, String vertical, String size) {
        // 调 gpt-image-2 出图，返回 Base64
        String base64 = chatGpt2ApiClient.generateImage(prompt.getContent(), size);
        if (base64 == null || base64.isBlank()) {
            throw new RuntimeException("gpt-image-2 未返回图片数据");
        }
        // decode
        byte[] imageBytes = Base64.getDecoder().decode(base64);
        // 上传 MinIO
        String objectKey = ObjectKeyBuilder.raw(vertical, prompt.getRunId(), prompt.getSeq(), IMAGE_EXT);
        storageService.upload(objectKey, imageBytes, IMAGE_CONTENT_TYPE);
        // 解析宽高
        int[] wh = parseSize(size);
        // 写 lf_generated_image
        GeneratedImage gi = new GeneratedImage();
        gi.setPromptId(prompt.getId());
        gi.setObjectKey(objectKey);
        gi.setWidth(wh[0]);
        gi.setHeight(wh[1]);
        generatedImageMapper.insert(gi);

        return new ImageGenerationResult(prompt.getId(), prompt.getSeq(),
                gi.getId(), objectKey, true, null);
    }

    /** 构造失败结果 */
    /** 构造失败结果 */
    private ImageGenerationResult fail(Prompt prompt, String error) {
        return new ImageGenerationResult(
                prompt.getId(), prompt.getSeq(), null, null, false, error);
    }

    /** 按 vertical 决定尺寸 */
    private String resolveSize(String vertical) {
        return VERTICAL_WALLPAPER.equals(vertical) ? SIZE_WALLPAPER : SIZE_DEFAULT;
    }

    /** 从 "1024x1792" 解析为 [1024, 1792] */
    private int[] parseSize(String size) {
        try {
            String[] parts = size.split("x");
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }
}
