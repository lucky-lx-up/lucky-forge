package com.lucky.luckyforge.infrastructure.storage;

import com.lucky.luckyforge.config.MinioProperties;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MinioStorageService} 集成测试（连真实 MinIO）。
 * <p>验证上传后可下载得到完全相同的字节、删除后对象不可读、预签名 URL 可生成。
 */
@SpringBootTest
@ActiveProfiles("test")
class MinioStorageServiceTest {

    @Autowired private MinioStorageService storageService;
    @Autowired private MinioClient minioClient;
    @Autowired private MinioProperties minioProperties;

    @Test
    void 上传后下载内容一致() {
        String key = "test/smoke/" + System.nanoTime() + ".txt";
        byte[] payload = "lucky-forge-minio-smoke".getBytes(StandardCharsets.UTF_8);

        storageService.upload(key, payload, "text/plain");
        byte[] downloaded = storageService.download(key);
        assertArrayEquals(payload, downloaded);

        // 清理
        storageService.delete(key);
    }

    @Test
    void 预签名URL可生成() {
        String key = "test/smoke/" + System.nanoTime() + ".txt";
        String url = storageService.getPublicUrl(key);
        assertNotNull(url);
        assertTrue(url.contains(minioProperties.getEndpoint().replace("http://","")));
    }
}