package com.lucky.luckyforge.infrastructure.chatgpt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucky.luckyforge.common.exception.ChatGptApiException;
import com.lucky.luckyforge.config.ChatGpt2ApiProperties;
import com.lucky.luckyforge.infrastructure.chatgpt.dto.ContentPart;
import com.lucky.luckyforge.infrastructure.chatgpt.dto.MultimodalMessage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChatGpt2ApiClient#chatCompletion(String, List)} 多模态重载的单元测试。
 * <p>用 MockWebServer 造服务端，验证：
 * <ul>
 *   <li>请求体 content 数组结构正确（含 image_url 元素，OpenAI 多模态协议）</li>
 *   <li>响应解析正确（首条文本返回）</li>
 *   <li>重试逻辑对多模态同样生效（5xx 后重试成功）</li>
 * </ul>
 */
class ChatGpt2ApiClientMultimodalTest {

    private MockWebServer server;
    private ChatGpt2ApiClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        client = new ChatGpt2ApiClient(props, objectMapper);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void 多模态请求体含image_url元素() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                {"choices":[{"message":{"role":"assistant","content":"风格特征"}}]}
                """).addHeader("Content-Type", "application/json"));

        List<MultimodalMessage> msgs = List.of(MultimodalMessage.user(List.of(
                ContentPart.ofText("分析这张图"),
                ContentPart.ofImage("http://example.com/a.jpg")
        )));

        String result = client.chatCompletion("你是风格分析师", msgs);

        assertEquals("风格特征", result);

        // 校验发出的请求体结构
        RecordedRequest recorded = server.takeRequest();
        JsonNode body = objectMapper.readTree(recorded.getBody().readUtf8());
        JsonNode msgNode = body.get("messages").get(1); // [0]=system, [1]=user
        assertEquals("user", msgNode.get("role").asText());
        // content 为数组
        assertTrue(msgNode.get("content").isArray(), "content 应为数组");
        // 含 text 与 image_url 两种部件
        JsonNode textPart = msgNode.get("content").get(0);
        assertEquals("text", textPart.get("type").asText());
        assertEquals("分析这张图", textPart.get("text").asText());
        JsonNode imagePart = msgNode.get("content").get(1);
        assertEquals("image_url", imagePart.get("type").asText());
        assertEquals("http://example.com/a.jpg",
                imagePart.get("image_url").get("url").asText());
        // text 部件不应含 image_url 字段（@JsonInclude NON_NULL）
        assertNull(textPart.get("image_url"));
    }

    @Test
    void 多模态系统提示词可选() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                {"choices":[{"message":{"role":"assistant","content":"ok"}}]}
                """).addHeader("Content-Type", "application/json"));

        List<MultimodalMessage> msgs = List.of(MultimodalMessage.user(
                List.of(ContentPart.ofImage("http://example.com/b.jpg"))));

        String result = client.chatCompletion(null, msgs);
        assertEquals("ok", result);

        // 无系统提示词时，messages 仅 1 条
        RecordedRequest recorded = server.takeRequest();
        JsonNode body = objectMapper.readTree(recorded.getBody().readUtf8());
        assertEquals(1, body.get("messages").size());
    }

    @Test
    void 多模态重试同样生效_五xx后成功() throws Exception {
        // 首次 500，第二次成功
        server.enqueue(new MockResponse().setResponseCode(500).setBody("err"));
        server.enqueue(new MockResponse().setBody("""
                {"choices":[{"message":{"role":"assistant","content":"重试后成功"}}]}
                """).addHeader("Content-Type", "application/json"));

        List<MultimodalMessage> msgs = List.of(MultimodalMessage.user(
                List.of(ContentPart.ofImage("http://example.com/c.jpg"))));

        String result = client.chatCompletion("s", msgs);
        assertEquals("重试后成功", result);
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void 多模态四xx不重试直接抛异常() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("unauthorized"));

        List<MultimodalMessage> msgs = List.of(MultimodalMessage.user(
                List.of(ContentPart.ofImage("http://example.com/d.jpg"))));

        ChatGptApiException ex = assertThrows(ChatGptApiException.class,
                () -> client.chatCompletion("s", msgs));
        assertEquals(401, ex.getStatusCode());
        assertEquals(1, server.getRequestCount());
    }
}
