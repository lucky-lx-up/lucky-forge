package com.lucky.luckyforge.infrastructure.persistence;

import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.entity.GeneratedImage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Prompt;
import com.lucky.luckyforge.infrastructure.persistence.entity.ReferenceImage;
import com.lucky.luckyforge.infrastructure.persistence.entity.Run;
import com.lucky.luckyforge.infrastructure.persistence.entity.Style;
import com.lucky.luckyforge.infrastructure.persistence.enums.BatchStatus;
import com.lucky.luckyforge.infrastructure.persistence.enums.RunStatus;
import com.lucky.luckyforge.infrastructure.persistence.enums.Vertical;
import com.lucky.luckyforge.infrastructure.persistence.mapper.BatchMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.GeneratedImageMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.PromptMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.ReferenceImageMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.RunMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.StyleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据访问层冒烟测试（连真实 MySQL）。
 * <p>验证前段 6 张表字段映射正确、BaseMapper CRUD 可用、逻辑删除（Style/Batch）自动过滤。
 * 用 @Transactional 自动回滚，不污染数据库。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PersistenceSmokeTest {

    @Autowired private StyleMapper styleMapper;
    @Autowired private BatchMapper batchMapper;
    @Autowired private RunMapper runMapper;
    @Autowired private ReferenceImageMapper referenceImageMapper;
    @Autowired private PromptMapper promptMapper;
    @Autowired private GeneratedImageMapper generatedImageMapper;

    @Test
    void 风格表CRUD与逻辑删除() {
        Style s = new Style();
        s.setName("测试风格-" + System.nanoTime());
        s.setVertical(Vertical.WALLPAPER.value());
        s.setDescription("色调温暖");
        assertEquals(1, styleMapper.insert(s));
        assertNotNull(s.getId());

        Style found = styleMapper.selectById(s.getId());
        assertNotNull(found);
        assertEquals("色调温暖", found.getDescription());

        // 逻辑删除：deleteById 对含 deletedAt 的表写入删除时间
        assertEquals(1, styleMapper.deleteById(s.getId()));
        // 查询自动过滤已删除行
        assertNull(styleMapper.selectById(s.getId()));
    }

    @Test
    void 批次表CRUD与逻辑删除() {
        Batch b = new Batch();
        b.setVertical(Vertical.WALLPAPER.value());
        b.setTheme("夜景");
        b.setTargetCount(8);
        b.setStatus(BatchStatus.DRAFT.value());
        assertEquals(1, batchMapper.insert(b));
        assertNotNull(b.getId());

        // styleId 可空
        assertNull(batchMapper.selectById(b.getId()).getStyleId());

        batchMapper.deleteById(b.getId());
        assertNull(batchMapper.selectById(b.getId()));
    }

    @Test
    void 运行表CRUD无逻辑删除() {
        Batch b = new Batch();
        b.setVertical(Vertical.WALLPAPER.value());
        b.setTargetCount(1);
        b.setStatus(BatchStatus.DRAFT.value());
        batchMapper.insert(b);

        Run r = new Run();
        r.setBatchId(b.getId());
        r.setStatus(RunStatus.PENDING.value());
        runMapper.insert(r);
        assertNotNull(r.getId());
        assertEquals(b.getId(), runMapper.selectById(r.getId()).getBatchId());

        // run 无 deletedAt，物理删除
        runMapper.deleteById(r.getId());
        assertNull(runMapper.selectById(r.getId()));
    }

    @Test
    void 参考图表CRUD() {
        Batch b = new Batch();
        b.setVertical(Vertical.WALLPAPER.value());
        b.setTargetCount(1);
        b.setStatus(BatchStatus.DRAFT.value());
        batchMapper.insert(b);

        ReferenceImage ri = new ReferenceImage();
        ri.setBatchId(b.getId());
        ri.setObjectKey("wallpaper/reference/1/test.jpg");
        ri.setSource("MANUAL");
        referenceImageMapper.insert(ri);
        assertNotNull(ri.getId());
        assertNotNull(referenceImageMapper.selectById(ri.getId()));
    }

    @Test
    void 提示词表CRUD() {
        Run r = new Run();
        r.setBatchId(0L);
        r.setStatus(RunStatus.PENDING.value());
        runMapper.insert(r);

        Prompt p = new Prompt();
        p.setRunId(r.getId());
        p.setSeq(1);
        p.setContent("极简风景壁纸");
        promptMapper.insert(p);
        assertNotNull(p.getId());
        assertEquals(1, promptMapper.selectById(p.getId()).getSeq());
    }

    @Test
    void 生成图表CRUD() {
        Prompt p = new Prompt();
        p.setRunId(0L);
        p.setSeq(1);
        p.setContent("test");
        promptMapper.insert(p);

        GeneratedImage gi = new GeneratedImage();
        gi.setPromptId(p.getId());
        gi.setObjectKey("wallpaper/raw/1/1.png");
        gi.setWidth(1080);
        gi.setHeight(1920);
        generatedImageMapper.insert(gi);
        assertNotNull(gi.getId());
        assertEquals(1080, generatedImageMapper.selectById(gi.getId()).getWidth());
    }
}