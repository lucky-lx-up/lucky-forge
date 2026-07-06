package com.lucky.luckyforge.common.exception;

import com.lucky.luckyforge.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GlobalExceptionHandler} 单元测试。
 * <p>直接调用 handler 方法，断言四类异常各自映射到正确 HTTP 状态码与 ApiResponse 字段。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void 业务异常映射为400() {
        BizException ex = new BizException("批次不存在");
        ResponseEntity<ApiResponse<?>> resp = handler.handleBizException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(ApiResponse.ERROR, resp.getBody().getResult());
        assertEquals("批次不存在", resp.getBody().getMessage());
        assertNull(resp.getBody().getData());
    }

    @Test
    void 参数非法异常映射为400() {
        IllegalArgumentException ex = new IllegalArgumentException("batchId 不能为空");
        ResponseEntity<ApiResponse<?>> resp = handler.handleIllegalArgumentException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(ApiResponse.ERROR, resp.getBody().getResult());
        assertEquals("batchId 不能为空", resp.getBody().getMessage());
    }

    @Test
    void chatgpt异常映射为502() {
        ChatGptApiException ex = new ChatGptApiException("重试耗尽", 500, "err", null);
        ResponseEntity<ApiResponse<?>> resp = handler.handleChatGptApiException(ex);

        assertEquals(HttpStatus.BAD_GATEWAY, resp.getStatusCode());
        assertEquals(ApiResponse.ERROR, resp.getBody().getResult());
    }

    @Test
    void 存储异常映射为500() {
        StorageException ex = new StorageException("上传失败: xxx");
        ResponseEntity<ApiResponse<?>> resp = handler.handleStorageException(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals(ApiResponse.ERROR, resp.getBody().getResult());
        assertEquals("上传失败: xxx", resp.getBody().getMessage());
    }

    @Test
    void 未捕获异常映射为500兜底() {
        Exception ex = new RuntimeException("空指针");
        ResponseEntity<ApiResponse<?>> resp = handler.handleException(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals(ApiResponse.ERROR, resp.getBody().getResult());
        // 兜底 message 为通用文案，不暴露内部堆栈
        assertEquals("服务器内部错误", resp.getBody().getMessage());
    }

    @Test
    void 静态工厂errorResponseEntity组装正确() {
        ResponseEntity<ApiResponse<?>> resp = GlobalExceptionHandler.errorResponseEntity("custom", HttpStatus.CONFLICT);

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals(ApiResponse.ERROR, resp.getBody().getResult());
        assertEquals("custom", resp.getBody().getMessage());
        assertNull(resp.getBody().getData());
    }

    // ===== ApiResponse 静态工厂补强测试 =====

    @Test
    void success工厂返回SUCCESS标识() {
        ApiResponse<String> resp = ApiResponse.success("payload");

        assertEquals(ApiResponse.SUCCESS, resp.getResult());
        assertEquals("payload", resp.getData());
    }

    @Test
    void success工厂自定义message() {
        ApiResponse<String> resp = ApiResponse.success("done", "payload");

        assertEquals(ApiResponse.SUCCESS, resp.getResult());
        assertEquals("done", resp.getMessage());
        assertEquals("payload", resp.getData());
    }

    @Test
    void error工厂返回ERROR标识且数据为空() {
        ApiResponse<String> resp = ApiResponse.error("失败");

        assertEquals(ApiResponse.ERROR, resp.getResult());
        assertEquals("失败", resp.getMessage());
        assertNull(resp.getData());
    }
}
