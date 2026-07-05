package com.lucky.luckyforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO 对象存储连接属性。
 * <p>绑定 application.yaml 中 {@code minio} 前缀的配置。
 */
@Data
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

    /** MinIO API 地址（如 http://192.168.2.137:9000） */
    private String endpoint;

    /** 访问密钥 */
    private String accessKey;

    /** 秘密密钥 */
    private String secretKey;

    /** 存储桶名 */
    private String bucket;
}