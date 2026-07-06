package com.lucky.luckyforge.infrastructure.persistence;

import com.lucky.luckyforge.infrastructure.persistence.entity.GeneratedImage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Prompt;
import com.lucky.luckyforge.infrastructure.persistence.entity.Score;
import com.lucky.luckyforge.infrastructure.persistence.entity.ScoreDimension;
import com.lucky.luckyforge.infrastructure.persistence.mapper.GeneratedImageMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.PromptMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.ScoreDimensionMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.ScoreMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Score / ScoreDimension 实体冒烟测试（连真实 MySQL）。
 * <p>验证字段映射、CRUD、lf_score 的 1:1 唯一键（uk_score_genimg）、
 * lf_score_dimension 的 (score_id, name) 唯一键。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScoreEntitySmokeTest {

    @Autowired private PromptMapper promptMapper;
    @Autowired private GeneratedImageMapper generatedImageMapper;
    @Autowired private ScoreMapper scoreMapper;
    @Autowired private ScoreDimensionMapper scoreDimensionMapper;

    @Test
    void score表CRUD与字段映射() {
        Long genImgId = newGeneratedImage();

        Score s = new Score();
        s.setGeneratedImageId(genImgId);
        s.setTotal(new BigDecimal("92.50"));
        s.setRemark("高质量壁纸");
        assertEquals(1, scoreMapper.insert(s));
        assertNotNull(s.getId());

        Score found = scoreMapper.selectById(s.getId());
        assertNotNull(found);
        assertEquals(genImgId, found.getGeneratedImageId());
        assertEquals(0, new BigDecimal("92.50").compareTo(found.getTotal()));
        assertEquals("高质量壁纸", found.getRemark());

        // 更新
        found.setTotal(new BigDecimal("88.00"));
        assertEquals(1, scoreMapper.updateById(found));
    }

    @Test
    void scoreDimension表CRUD与字段映射() {
        Long genImgId = newGeneratedImage();
        Long scoreId = newScore(genImgId);

        ScoreDimension d = new ScoreDimension();
        d.setScoreId(scoreId);
        d.setName("composition");
        d.setValue(new BigDecimal("92"));
        assertEquals(1, scoreDimensionMapper.insert(d));
        assertNotNull(d.getId());

        ScoreDimension found = scoreDimensionMapper.selectById(d.getId());
        assertEquals(scoreId, found.getScoreId());
        assertEquals("composition", found.getName());
        assertEquals(0, new BigDecimal("92").compareTo(found.getValue()));
    }

    @Test
    void score的1对1唯一键_同图二次插入冲突() {
        Long genImgId = newGeneratedImage();
        newScore(genImgId); // 第一次插入

        // 第二次插入同 generatedImageId 应冲突
        Score dup = new Score();
        dup.setGeneratedImageId(genImgId);
        dup.setTotal(new BigDecimal("50"));
        assertThrows(DuplicateKeyException.class, () -> scoreMapper.insert(dup));
    }

    @Test
    void scoreDimension的scoreId加name唯一键() {
        Long genImgId = newGeneratedImage();
        Long scoreId = newScore(genImgId);

        ScoreDimension d1 = new ScoreDimension();
        d1.setScoreId(scoreId);
        d1.setName("color");
        d1.setValue(new BigDecimal("90"));
        scoreDimensionMapper.insert(d1);

        // 同 (scoreId, name) 二次插入应冲突
        ScoreDimension d2 = new ScoreDimension();
        d2.setScoreId(scoreId);
        d2.setName("color");
        d2.setValue(new BigDecimal("80"));
        assertThrows(DuplicateKeyException.class, () -> scoreDimensionMapper.insert(d2));
    }

    @Test
    void 删除score后其维度应可独立删除() {
        // 验证覆盖式打分的删除顺序：先 dimension 后 score
        Long genImgId = newGeneratedImage();
        Long scoreId = newScore(genImgId);

        ScoreDimension d = new ScoreDimension();
        d.setScoreId(scoreId);
        d.setName("clarity");
        d.setValue(new BigDecimal("85"));
        scoreDimensionMapper.insert(d);

        // 先删 dimension
        assertEquals(1, scoreDimensionMapper.deleteById(d.getId()));
        // 再删 score
        assertEquals(1, scoreMapper.deleteById(scoreId));
        assertNull(scoreMapper.selectById(scoreId));
    }

    // ===== 辅助：创建一条生成图（需要先有 prompt） =====

    private Long newGeneratedImage() {
        Prompt p = new Prompt();
        p.setRunId(0L);
        p.setSeq((int) (System.nanoTime() % 100000));
        p.setContent("test");
        promptMapper.insert(p);

        GeneratedImage gi = new GeneratedImage();
        gi.setPromptId(p.getId());
        gi.setObjectKey("test/smoke/" + System.nanoTime() + ".png");
        gi.setWidth(1024);
        gi.setHeight(1792);
        generatedImageMapper.insert(gi);
        return gi.getId();
    }

    private Long newScore(Long genImgId) {
        Score s = new Score();
        s.setGeneratedImageId(genImgId);
        s.setTotal(new BigDecimal("90"));
        s.setRemark("test");
        scoreMapper.insert(s);
        return s.getId();
    }
}
