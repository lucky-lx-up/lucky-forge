package com.lucky.luckyforge.application.packageassembler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lucky.luckyforge.application.packageassembler.dto.PackageAssemblyResponse;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.infrastructure.chatgpt.ChatGpt2ApiClient;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.entity.GeneratedImage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Package;
import com.lucky.luckyforge.infrastructure.persistence.entity.PackageImage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Prompt;
import com.lucky.luckyforge.infrastructure.persistence.entity.Run;
import com.lucky.luckyforge.infrastructure.persistence.entity.Score;
import com.lucky.luckyforge.infrastructure.persistence.entity.Style;
import com.lucky.luckyforge.infrastructure.persistence.mapper.*;
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
 * {@link PackageAssemblerService} 集成测试（mock chatgpt2api，连真实 MySQL）。
 * <p>PackageAssembler 在主线程执行（@Transactional），故可正常查 DB 验证落库。
 *
 * <p>覆盖：正常打包 + sort_order + 覆盖式 + run 无打分拒绝 + gpt 非法 JSON。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PackageAssemblerServiceIT {

    @Autowired private PackageAssemblerService packageAssemblerService;
    @Autowired private BatchMapper batchMapper;
    @Autowired private StyleMapper styleMapper;
    @Autowired private RunMapper runMapper;
    @Autowired private PromptMapper promptMapper;
    @Autowired private GeneratedImageMapper generatedImageMapper;
    @Autowired private ScoreMapper scoreMapper;
    @Autowired private PackageMapper packageMapper;
    @Autowired private PackageImageMapper packageImageMapper;

    @MockBean private ChatGpt2ApiClient chatGpt2ApiClient;

    @Test
    void 正常打包_sortOrder按分数降序() {
        Long[] ids = setupRunWithScores(new int[]{80, 95, 70}, 2); // targetCount=2
        Long runId = ids[1];

        when(chatGpt2ApiClient.chatCompletion(anyString(), anyList()))
                .thenReturn("{\"title\":\"梦幻日落\",\"tags\":[\"渐变\",\"极简\",\"日落\"]}");

        PackageAssemblyResponse resp = packageAssemblerService.assemble(runId);

        // package 落库
        assertEquals("梦幻日落", resp.title());
        assertEquals(3, resp.tags().size());
        // 只取 Top 2（targetCount=2）
        assertEquals(2, resp.images().size());
        // sort_order：95 分第一（封面），80 分第二
        assertEquals(0, resp.images().get(0).sortOrder());
        assertEquals(0, new BigDecimal("95").compareTo(resp.images().get(0).score()));
        assertEquals(1, resp.images().get(1).sortOrder());
        assertEquals(0, new BigDecimal("80").compareTo(resp.images().get(1).score()));

        // DB 校验
        List<Package> pkgs = packageMapper.selectList(new LambdaQueryWrapper<Package>()
                .eq(Package::getBatchId, ids[0]));
        assertEquals(1, pkgs.size());
        assertEquals("梦幻日落", pkgs.get(0).getTitle());
        List<PackageImage> pkgImgs = packageImageMapper.selectList(
                new LambdaQueryWrapper<PackageImage>().eq(PackageImage::getPackageId, pkgs.get(0).getId()));
        assertEquals(2, pkgImgs.size());

        // run.currentStep=PACKAGE
        Run run = runMapper.selectById(runId);
        assertEquals("PACKAGE", run.getCurrentStep());
    }

    @Test
    void 覆盖式打包_二次覆盖旧数据() {
        Long[] ids = setupRunWithScores(new int[]{90}, 1);
        Long runId = ids[1];

        when(chatGpt2ApiClient.chatCompletion(anyString(), anyList()))
                .thenReturn("{\"title\":\"旧标题\",\"tags\":[\"旧\"]}");
        packageAssemblerService.assemble(runId);

        // 旧 package 数量
        long oldCount = packageMapper.selectCount(null);

        // 第二次打包
        reset(chatGpt2ApiClient);
        when(chatGpt2ApiClient.chatCompletion(anyString(), anyList()))
                .thenReturn("{\"title\":\"新标题\",\"tags\":[\"新\"]}");
        PackageAssemblyResponse resp2 = packageAssemblerService.assemble(runId);

        assertEquals("新标题", resp2.title());
        // 未删除的 package 仍 1 条（旧的逻辑删除了）
        List<Package> active = packageMapper.selectList(new LambdaQueryWrapper<Package>()
                .eq(Package::getBatchId, ids[0]));
        assertEquals(1, active.size());
        assertEquals("新标题", active.get(0).getTitle());
    }

    @Test
    void run无打分时拒绝() {
        Batch batch = newBatch(2);
        batchMapper.insert(batch);
        Run run = newRun(batch.getId());
        runMapper.insert(run);

        BizException ex = assertThrows(BizException.class,
                () -> packageAssemblerService.assemble(run.getId()));
        assertTrue(ex.getMessage().contains("无打分"));
    }

    @Test
    void gpt返回非法JSON时抛异常() {
        Long[] ids = setupRunWithScores(new int[]{90}, 1);
        Long runId = ids[1];

        when(chatGpt2ApiClient.chatCompletion(anyString(), anyList())).thenReturn("不是JSON");

        BizException ex = assertThrows(BizException.class,
                () -> packageAssemblerService.assemble(runId));
        assertTrue(ex.getMessage().contains("解析"));
    }

    // ===== 辅助 =====

    /**
     * 创建 batch + style + run + prompt + N 图 + 对应 score。
     * @param scores 各图的分数
     * @param targetCount batch.targetCount（决定 TopN）
     * @return [batchId, runId]
     */
    private Long[] setupRunWithScores(int[] scores, int targetCount) {
        Style style = new Style();
        style.setName("测试风格-" + System.nanoTime());
        style.setVertical("WALLPAPER");
        style.setDescription("暖色调");
        styleMapper.insert(style);

        Batch batch = newBatch(targetCount);
        batch.setStyleId(style.getId());
        batchMapper.insert(batch);

        Run run = newRun(batch.getId());
        runMapper.insert(run);

        Prompt prompt = new Prompt();
        prompt.setRunId(run.getId());
        prompt.setSeq(1);
        prompt.setContent("test");
        promptMapper.insert(prompt);

        for (int s : scores) {
            GeneratedImage gi = new GeneratedImage();
            gi.setPromptId(prompt.getId());
            gi.setObjectKey("test/pkg/" + System.nanoTime() + ".png");
            gi.setWidth(1024);
            gi.setHeight(1792);
            generatedImageMapper.insert(gi);

            Score score = new Score();
            score.setGeneratedImageId(gi.getId());
            score.setTotal(new BigDecimal(s));
            score.setRemark("test");
            scoreMapper.insert(score);
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
        r.setCurrentStep("SCORE");
        return r;
    }
}
