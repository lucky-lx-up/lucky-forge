package com.lucky.luckyforge.infrastructure.persistence;

import com.lucky.luckyforge.infrastructure.persistence.entity.Batch;
import com.lucky.luckyforge.infrastructure.persistence.entity.Package;
import com.lucky.luckyforge.infrastructure.persistence.entity.PackageImage;
import com.lucky.luckyforge.infrastructure.persistence.mapper.BatchMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.PackageImageMapper;
import com.lucky.luckyforge.infrastructure.persistence.mapper.PackageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Package / PackageImage 实体冒烟测试（连真实 MySQL）。
 * <p>验证字段映射、CRUD、Package 的逻辑删除（deletedAt）、PackageImage 的唯一键。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PackageEntitySmokeTest {

    @Autowired private BatchMapper batchMapper;
    @Autowired private PackageMapper packageMapper;
    @Autowired private PackageImageMapper packageImageMapper;

    @Test
    void package表CRUD与字段映射() {
        Long batchId = newBatch();
        Package p = new Package();
        p.setBatchId(batchId);
        p.setVertical("WALLPAPER");
        p.setTitle("梦幻日落壁纸");
        p.setTags("[\"渐变\",\"极简\",\"日落\"]");
        p.setStatus("DRAFT");
        assertEquals(1, packageMapper.insert(p));
        assertNotNull(p.getId());

        Package found = packageMapper.selectById(p.getId());
        assertNotNull(found);
        assertEquals("梦幻日落壁纸", found.getTitle());
        // MySQL JSON 字段返回时会自动格式化（加空格），只验证内容包含关键词
        assertNotNull(found.getTags());
        assertTrue(found.getTags().contains("渐变") && found.getTags().contains("日落"));
        assertEquals("DRAFT", found.getStatus());
        assertNull(found.getDeletedAt(), "新建 package 的 deletedAt 应为 null");
    }

    @Test
    void package逻辑删除生效() {
        Long batchId = newBatch();
        Package p = newPackage(batchId);
        packageMapper.insert(p);

        // 逻辑删除
        assertEquals(1, packageMapper.deleteById(p.getId()));
        // 查询自动过滤已删除行
        assertNull(packageMapper.selectById(p.getId()), "逻辑删除后 selectById 应返回 null");
    }

    @Test
    void packageImage表CRUD与字段映射() {
        Long batchId = newBatch();
        Long packageId = newPackageId(batchId);

        PackageImage pi = new PackageImage();
        pi.setPackageId(packageId);
        pi.setGeneratedImageId(System.nanoTime()); // 测试用，不真实关联
        pi.setSortOrder(0);
        assertEquals(1, packageImageMapper.insert(pi));
        assertNotNull(pi.getId());

        PackageImage found = packageImageMapper.selectById(pi.getId());
        assertEquals(packageId, found.getPackageId());
        assertEquals(0, found.getSortOrder());
    }

    @Test
    void packageImage的唯一键_同包同图二次插入冲突() {
        Long batchId = newBatch();
        Long packageId = newPackageId(batchId);
        long genImgId = System.nanoTime();

        PackageImage pi1 = new PackageImage();
        pi1.setPackageId(packageId);
        pi1.setGeneratedImageId(genImgId);
        pi1.setSortOrder(0);
        packageImageMapper.insert(pi1);

        PackageImage pi2 = new PackageImage();
        pi2.setPackageId(packageId);
        pi2.setGeneratedImageId(genImgId);
        pi2.setSortOrder(1);
        assertThrows(DuplicateKeyException.class, () -> packageImageMapper.insert(pi2));
    }

    @Test
    void 同一张图可进不同包() {
        // 验证 M:N：同一 generatedImageId 关联两个不同 package，均合法
        Long batchId = newBatch();
        long genImgId = System.nanoTime();
        Long pkg1 = newPackageId(batchId);
        Long pkg2 = newPackageId(batchId);

        PackageImage pi1 = new PackageImage();
        pi1.setPackageId(pkg1);
        pi1.setGeneratedImageId(genImgId);
        pi1.setSortOrder(0);
        packageImageMapper.insert(pi1);

        PackageImage pi2 = new PackageImage();
        pi2.setPackageId(pkg2);
        pi2.setGeneratedImageId(genImgId);
        pi2.setSortOrder(0);
        packageImageMapper.insert(pi2); // 不同 package，不冲突
        assertNotNull(pi2.getId());
    }

    private Long newBatch() {
        Batch b = new Batch();
        b.setVertical("WALLPAPER");
        b.setTargetCount(1);
        b.setStatus("DRAFT");
        batchMapper.insert(b);
        return b.getId();
    }

    private Package newPackage(Long batchId) {
        Package p = new Package();
        p.setBatchId(batchId);
        p.setVertical("WALLPAPER");
        p.setTitle("test-" + System.nanoTime());
        p.setStatus("DRAFT");
        return p;
    }

    private Long newPackageId(Long batchId) {
        Package p = newPackage(batchId);
        packageMapper.insert(p);
        return p.getId();
    }
}
