package com.lucky.luckyforge.application.packagequery;

import com.lucky.luckyforge.application.packagequery.dto.PackageDetail;
import com.lucky.luckyforge.application.packagequery.dto.PackageImageDetail;
import com.lucky.luckyforge.application.packagequery.dto.PackageSummary;
import com.lucky.luckyforge.common.exception.BizException;
import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.entity.GeneratedImage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Package;
import com.lucky.luckyforge.infrastructure.persistence.entity.PackageImage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Prompt;
import com.lucky.luckyforge.infrastructure.persistence.entity.Score;
import com.lucky.luckyforge.infrastructure.persistence.entity.ScoreDimension;
import com.lucky.luckyforge.infrastructure.persistence.mapper.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PackageQueryService} 集成测试（连真实 MySQL）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PackageQueryServiceIT {

    @Autowired private PackageQueryService packageQueryService;
    @Autowired private BatchMapper batchMapper;
    @Autowired private PackageMapper packageMapper;
    @Autowired private PackageImageMapper packageImageMapper;
    @Autowired private GeneratedImageMapper generatedImageMapper;
    @Autowired private PromptMapper promptMapper;
    @Autowired private ScoreMapper scoreMapper;
    @Autowired private ScoreDimensionMapper scoreDimensionMapper;

    @Test
    void 查素材包详情_含图片与预览URL() {
        Long[] ids = setupPackageWithImages(2);
        Long packageId = ids[0];

        PackageDetail detail = packageQueryService.getPackageDetail(packageId);

        assertEquals(packageId, detail.id());
        assertEquals("测试素材包", detail.title());
        assertEquals(2, detail.images().size());
        // 按 sortOrder 升序
        assertEquals(0, detail.images().get(0).sortOrder());
        // 预览 URL 非空
        assertNotNull(detail.images().get(0).previewUrl());
        assertTrue(detail.images().get(0).previewUrl().startsWith("http"));
        // score 有值
        assertNotNull(detail.images().get(0).score());
        assertNotNull(detail.images().get(0).remark(), "应有评语");
        // 维度分明细（4 个）
        assertNotNull(detail.images().get(0).dimensions(), "应有维度分明细");
        assertEquals(4, detail.images().get(0).dimensions().size(), "应有 4 个维度");
        // tags 解析正确
        assertEquals(2, detail.tags().size());
    }

    @Test
    void 查素材包详情_不存在时抛异常() {
        assertThrows(BizException.class, () -> packageQueryService.getPackageDetail(99999999L));
    }

    @Test
    void 查batch的素材包列表() {
        Long batchId = setupPackageWithImages(1)[1];
        List<PackageSummary> list = packageQueryService.listPackagesByBatch(batchId);
        assertEquals(1, list.size());
        assertEquals("测试素材包", list.get(0).title());
        assertEquals(1, list.get(0).imageCount());
    }

    /** 创建 batch + package + N 图片关联，返回 [packageId, batchId] */
    private Long[] setupPackageWithImages(int imageCount) {
        Batch batch = new Batch();
        batch.setVertical("WALLPAPER");
        batch.setTargetCount(2);
        batch.setStatus("DRAFT");
        batchMapper.insert(batch);

        Package pkg = new Package();
        pkg.setBatchId(batch.getId());
        pkg.setVertical("WALLPAPER");
        pkg.setTitle("测试素材包");
        pkg.setTags("[\"标签1\",\"标签2\"]");
        pkg.setStatus("DRAFT");
        packageMapper.insert(pkg);

        Prompt prompt = new Prompt();
        prompt.setRunId(0L);
        prompt.setSeq((int)(System.nanoTime() % 100000));
        prompt.setContent("test");
        promptMapper.insert(prompt);

        for (int i = 0; i < imageCount; i++) {
            GeneratedImage gi = new GeneratedImage();
            gi.setPromptId(prompt.getId());
            gi.setObjectKey("test/query/" + System.nanoTime() + "_" + i + ".png");
            gi.setWidth(1024);
            gi.setHeight(1792);
            generatedImageMapper.insert(gi);

            PackageImage pi = new PackageImage();
            pi.setPackageId(pkg.getId());
            pi.setGeneratedImageId(gi.getId());
            pi.setSortOrder(i);
            packageImageMapper.insert(pi);

            Score score = new Score();
            score.setGeneratedImageId(gi.getId());
            score.setTotal(new BigDecimal("90"));
            score.setRemark("test");
            scoreMapper.insert(score);

            // 插 4 个维度分
            for (String dim : new String[]{"composition", "color", "clarity", "relevance"}) {
                ScoreDimension sd = new ScoreDimension();
                sd.setScoreId(score.getId());
                sd.setName(dim);
                sd.setValue(new BigDecimal("88"));
                scoreDimensionMapper.insert(sd);
            }
        }
        return new Long[]{pkg.getId(), batch.getId()};
    }
}
