package com.lucky.luckyforge.application.styleanalysis;

import com.lucky.luckyforge.application.referenceimage.ReferenceImageService;
import com.lucky.luckyforge.application.referenceimage.dto.ReferenceImageUploadResponse;
import com.lucky.luckyforge.application.styleanalysis.dto.StyleAnalysisResponse;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.enums.BatchStatus;
import com.lucky.luckyforge.infrastructure.persistence.enums.Vertical;
import com.lucky.luckyforge.infrastructure.persistence.mapper.BatchMapper;
import com.lucky.luckyforge.infrastructure.storage.MinioStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StyleAnalyzer 真实联调测试（连真实 chatgpt2api，不 mock）。
 *
 * <p>与 {@link StyleAnalysisServiceIT}（mock chatgpt2api）不同，本测试调用真实的
 * gpt-5.5 多模态服务，验证端到端真实通路：参考图 → MinIO 预签名 URL → gpt-5.5 视觉理解
 * → 风格 JSON 解析 → lf_style 落库 → batch.styleId 回填。
 *
 * <p><b>启用条件</b>：仅当环境变量 {@code LIVE_CHATGPT2API=on} 时才运行。
 * 这样默认 {@code mvn test} 不会触发（避免常规构建消耗真实 AI 服务额度）；
 * 手动联调时设置该环境变量即可启用：
 * <pre>
 * LIVE_CHATGPT2API=on ./mvnw.cmd -Dtest=StyleAnalysisLiveIT test
 * </pre>
 *
 * <p>真实 chatgpt2api 地址与 key 通过 {@link TestPropertySource} 注入，
 * 不写入 git 追踪的 application-test.yaml，避免敏感信息入库。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // 真实 chatgpt2api 服务地址与 key（个人学习联调用，不入库默认配置）
        "chatgpt2api.base-url=http://192.168.2.137:13000",
        "chatgpt2api.api-key=00000000",
        "chatgpt2api.chat-timeout-seconds=90"
})
// 仅当显式设置环境变量 LIVE_CHATGPT2API=on 时才运行本测试类
@EnabledIfEnvironmentVariable(named = "LIVE_CHATGPT2API", matches = "on")
class StyleAnalysisLiveIT {

    @Autowired private ReferenceImageService referenceImageService;
    @Autowired private StyleAnalysisService styleAnalysisService;
    @Autowired private BatchMapper batchMapper;
    @Autowired private MinioStorageService storageService;

    @Test
    @Transactional
    void 真实联调_投喂参考图并提炼风格() throws Exception {
        // 1. 创建 batch
        Batch batch = new Batch();
        batch.setVertical(Vertical.WALLPAPER.value());
        batch.setTargetCount(4);
        batch.setStatus(BatchStatus.DRAFT.value());
        batchMapper.insert(batch);

        // 2. 上传 2 张测试图（sunset + ocean）作为参考图
        var sunsetRes = new ClassPathResource("liveit/sunset.jpg");
        var oceanRes = new ClassPathResource("liveit/ocean.jpg");
        var sunsetFile = new MockMultipartFile(
                "files", "sunset.jpg", "image/jpeg", sunsetRes.getInputStream().readAllBytes());
        var oceanFile = new MockMultipartFile(
                "files", "ocean.jpg", "image/jpeg", oceanRes.getInputStream().readAllBytes());

        List<ReferenceImageUploadResponse> uploads =
                referenceImageService.uploadReferences(batch.getId(), List.of(sunsetFile, oceanFile));
        List<String> objectKeys = uploads.stream().map(ReferenceImageUploadResponse::objectKey).toList();

        try {
            // 3. 真实调用 StyleAnalyzer（连真实 chatgpt2api）
            System.out.println("=== 触发真实风格提炼（batchId=" + batch.getId() + "）===");
            StyleAnalysisResponse resp = styleAnalysisService.analyze(batch.getId());

            // 4. 打印真实结果（联调观察用）
            System.out.println("=== gpt-5.5 返回的风格 ===");
            System.out.println("  styleId    : " + resp.styleId());
            System.out.println("  name       : " + resp.name());
            System.out.println("  description: " + resp.description());
            System.out.println("  styleJson  : " + resp.styleJson());

            // 5. 断言关键结果
            assertNotNull(resp.styleId(), "应成功写入 lf_style 并返回 id");
            assertNotNull(resp.name(), "风格名称不应为空");
            assertFalse(resp.name().isBlank(), "风格名称不应为空白");
            assertNotNull(resp.description(), "风格描述不应为空");

            // batch.styleId 应已回填
            Batch updated = batchMapper.selectById(batch.getId());
            assertEquals(resp.styleId(), updated.getStyleId(), "batch.styleId 应已回填");

            System.out.println("=== 联调通过：lf_style 落库 + batch.styleId 回填 ✓ ===");
        } finally {
            // 清理 MinIO（DB 由 @Transactional 回滚）
            objectKeys.forEach(k -> {
                try { storageService.delete(k); } catch (Exception ignored) { }
            });
        }
    }
}
