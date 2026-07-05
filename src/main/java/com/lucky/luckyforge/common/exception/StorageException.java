package com.lucky.luckyforge.common.exception;

/**
 * MinIO 对象存储操作失败异常。
 * <p>当 {@code MinioStorageService} 的上传/下载/删除等操作失败时抛出。
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}