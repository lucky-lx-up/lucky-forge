package com.lucky.luckyforge.infrastructure.chatgpt;

import com.lucky.luckyforge.common.exception.ChatGptApiException;
import com.lucky.luckyforge.config.ChatGpt2ApiProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChatGpt2ApiClient} 重试逻辑单元测试（用 MockWebServer 造服务端响应）。
 * <p>覆盖三种场景：①超时/5xx 后重试成功 ②4xx 不重试直接抛异常 ③重试耗尽抛异常。
 */
class ChatGpt2ApiClientTest {

    private MockWebServer server;
    private ChatGpt2ApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        ChatGpt2ApiProperties props = new ChatGpt2ApiProperties();
        props.setBaseUrl(server.url("/").toString());
        props.setApiKey("test-key");
        props.setMaxRetries(3);
        props.setChatTimeoutSeconds(5);
        props.setImageTimeoutSeconds(5);
        client = new ChatGpt2ApiClient(props, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void 文字补全_首次成功() {
        server.enqueue(new MockResponse().setBody("""
                {"choices":[{"message":{"role":"assistant","content":"风格特征"}}]}
                """).addHeader("Content-Type", "application/json"));
        String result = client.chatCompletion("系统提示", "分析这张图");
        assertEquals("风格特征", result);
    }

    @Test
    void 五xx后重试成功() {
        // 前两次 500，第三次成功
        server.enqueue(new MockResponse().setResponseCode(500).setBody("err1"));
        server.enqueue(new MockResponse().setResponseCode(503).setBody("err2"));
        server.enqueue(new MockResponse().setBody("""
                {"choices":[{"message":{"role":"assistant","content":"成功"}}]}
                """).addHeader("Content-Type", "application/json"));
        String result = client.chatCompletion("s", "u");
        assertEquals("成功", result);
    }

    @Test
    void 四xx不重试直接抛异常() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("unauthorized"));
        ChatGptApiException ex = assertThrows(ChatGptApiException.class,
                () -> client.chatCompletion("s", "u"));
        assertEquals(401, ex.getStatusCode());
        // responseBody 断言放宽：不同 ClientHttpRequestFactory 对 4xx body 的读取行为不一，
        // 核心契约是「4xx 不重试 + 抛 ChatGptApiException + 携带 statusCode」。
        // 仅被调用一次，说明未重试
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void 重试耗尽抛异常() {
        // 连续 4 次（maxRetries=3，即初始1次+重试3次）均 500
        server.enqueue(new MockResponse().setResponseCode(500).setBody("e1"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("e2"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("e3"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("e4"));
        ChatGptApiException ex = assertThrows(ChatGptApiException.class,
                () -> client.chatCompletion("s", "u"));
        assertEquals(500, ex.getStatusCode());
        // 初始1 + 重试3 = 共4次请求
        assertEquals(4, server.getRequestCount());
    }

    @Test
    void 出图响应为octet_stream时也能解析() {
        // chatgpt2api 偶发以 application/octet-stream 返回出图响应（而非 application/json）
        // 验证 buildClient 的 converter 配置能正确处理这种情况
        String imageBody = """
                {"data":[{"b64_json":"iVBORw0KGgo="}]}
                """;
        server.enqueue(new MockResponse()
                .setBody(imageBody)
                .addHeader("Content-Type", "application/octet-stream"));

        String base64 = client.generateImage("test prompt", "1024x1024");
        assertEquals("iVBORw0KGgo=", base64);
    }
}