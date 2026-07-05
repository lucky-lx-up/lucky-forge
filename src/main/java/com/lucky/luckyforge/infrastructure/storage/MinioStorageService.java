package com.lucky.luckyforge.infrastructure.storage;

import com.lucky.luckyforge.common.exception.StorageException;
import com.lucky.luckyforge.config.MinioProperties;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;

/**
 * MinIO 统一存储服务。
 * <p>对外暴露 upload/download/delete/getPublicUrl 四个方法，所有图片（参考图/生成图/
 * 打分图/素材包）共用此服务。bucket 在配置中固定，由 {@code MinioConfig} 初始化时校验/创建。
 *
 * <p>路径由 {@link ObjectKeyBuilder} 统一生成，本服务不感知业务语义。
 */
@Component
public class MinioStorageService {

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public MinioStorageService(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    /**
     * 上传图片字节到指定 object_key。
     *
     * @param objectKey   对象路径（由 ObjectKeyBuilder 生成）
     * @param bytes       图片字节数据
     * @param contentType MIME 类型（如 image/png）
     */
    public void upload(String objectKey, byte[] bytes, String contentType) {
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .stream(is, bytes.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new StorageException("上传失败: " + objectKey, e);
        }
    }

    /**
     * 下载指定 object_key 的图片字节。
     *
     * @param objectKey 对象路径
     * @return 图片字节数据
     */
    public byte[] download(String objectKey) {
        try (InputStream is = minioClient.getObject(GetObjectArgs.builder()
                .bucket(properties.getBucket())
                .object(objectKey)
                .build())) {
            return is.readAllBytes();
        } catch (ErrorResponseException e) {
            throw new StorageException("对象不存在: " + objectKey, e);
        } catch (Exception e) {
            throw new StorageException("下载失败: " + objectKey, e);
        }
    }

    /**
     * 删除指定 object_key 的对象。
     *
     * @param objectKey 对象路径
     */
    public void delete(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new StorageException("删除失败: " + objectKey, e);
        }
    }

    /**
     * 获取预签名访问 URL（默认有效期 1 小时）。
     * <p>首版用预签名而非公开桶，安全性更好；公开访问待发布环节按需开启。
     *
     * @param objectKey 对象路径
     * @return 预签名 URL
     */
    public String getPublicUrl(String objectKey) {
        try {
            return minioClient.getPresignedObjectUrl(
                    io.minio.GetPresignedObjectUrlArgs.builder()
                            .method(io.minio.http.Method.GET)
                            .bucket(properties.getBucket())
                            .object(objectKey)
                            .expiry((int) Duration.ofHours(1).getSeconds())
                            .build());
        } catch (Exception e) {
            throw new StorageException("生成预签名 URL 失败: " + objectKey, e);
        }
    }
}