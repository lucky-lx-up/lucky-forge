package com.lucky.luckyforge.common.exception;

import com.lucky.luckyforge.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 * <p>统一拦截 Controller 抛出的异常，转换为 {@code ResponseEntity<ApiResponse<?>>}，
 * 让所有错误响应遵循统一结构（result=ERROR + message）。
 *
 * <p>异常到 HTTP 状态码映射（语义贴合）：
 * <ul>
 *   <li>{@link BizException} → 400（业务规则不满足，如批次不存在/参考图为空）</li>
 *   <li>{@link IllegalArgumentException} → 400（DTO 紧凑构造器校验失败）</li>
 *   <li>{@link ChatGptApiException} → 502（上游 AI 服务故障）</li>
 *   <li>{@link StorageException} → 500（MinIO 存储故障）</li>
 *   <li>其余未捕获 {@link Exception} → 500 兜底</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 构造错误响应实体（工具方法，供需要自定义状态码的调用方使用）。
     *
     * @param message 错误信息
     * @param status  HTTP 状态码
     * @return 统一错误响应
     */
    public static ResponseEntity<ApiResponse<?>> errorResponseEntity(String message, HttpStatus status) {
        ApiResponse<?> response = ApiResponse.error(message);
        return new ResponseEntity<>(response, status);
    }

    /**
     * 业务异常：业务规则不满足（如批次不存在、参考图为空、参考图超限）。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<?>> handleBizException(BizException ex) {
        log.warn("业务异常: {}", ex.getMessage());
        return errorResponseEntity(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    /**
     * 参数非法异常：DTO 紧凑构造器校验失败（如非空字段为空）。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<?>> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("参数非法: {}", ex.getMessage());
        return errorResponseEntity(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    /**
     * chatgpt2api 调用最终失败：重试耗尽或不可重试的 4xx。
     * <p>视为上游网关错误（502）。
     */
    @ExceptionHandler(ChatGptApiException.class)
    public ResponseEntity<ApiResponse<?>> handleChatGptApiException(ChatGptApiException ex) {
        log.error("chatgpt2api 调用失败 (status={}): {}", ex.getStatusCode(), ex.getMessage());
        return errorResponseEntity(ex.getMessage(), HttpStatus.BAD_GATEWAY);
    }

    /**
     * MinIO 存储异常：上传/下载/删除失败。
     */
    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ApiResponse<?>> handleStorageException(StorageException ex) {
        log.error("存储异常: {}", ex.getMessage(), ex);
        return errorResponseEntity(ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * 未捕获异常兜底：避免向前端暴露内部堆栈。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleException(Exception ex) {
        log.error("未捕获异常", ex);
        return errorResponseEntity("服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
