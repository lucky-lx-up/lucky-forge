package com.lucky.luckyforge.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.lucky.luckyforge.common.exception.StorageException;

/**
 * MinIO 配置：装配 {@link MinioClient} Bean。
 * <p>构造时校验配置的 bucket 是否存在，不存在则自动创建（首版兜底，降低首次启动门槛）。
 */
@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

    /**
     * 构建 MinIO 客户端并校验/创建存储桶。
     */
    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        MinioClient client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
        // 兜底：bucket 不存在则创建
        try {
            boolean exists = client.bucketExists(
                    BucketExistsArgs.builder().bucket(properties.getBucket()).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
            }
        } catch (Exception e) {
            throw new StorageException("初始化 MinIO bucket 失败: " + properties.getBucket(), e);
        }
        return client;
    }
}