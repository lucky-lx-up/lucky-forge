package com.lucky.luckyforge.infrastructure.chatgpt.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 多模态消息内容部件（OpenAI chat completions 多模态协议）。
 * <p>对应请求体 {@code messages[].content[]} 的单个元素，支持两种类型：
 * <ul>
 *   <li>{@code text}：纯文本，序列化为 {@code {type:"text", text:"..."}}</li>
 *   <li>{@code image_url}：图片 URL，序列化为 {@code {type:"image_url", image_url:{url:"..."}}}</li>
 * </ul>
 *
 * <p>字段命名与 OpenAI 协议对齐：Java 字段 {@code imageUrl} 通过
 * {@link JsonProperty} 映射为协议要求的 snake_case {@code image_url}。
 * 用 {@link JsonInclude.Include#NON_NULL} 确保未设字段不出现在 JSON 中
 * （text 部件不出 imageUrl，image_url 部件不出 text）。
 *
 * @param type     类型：{@link #TYPE_TEXT} 或 {@link #TYPE_IMAGE_URL}
 * @param text     文本内容（仅 type=text 时非空）
 * @param imageUrl 图片 URL 容器（仅 type=image_url 时非空）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentPart(
        String type,
        String text,
        @JsonProperty("image_url") ImageUrl imageUrl
) {

    /** 文本类型标识 */
    public static final String TYPE_TEXT = "text";
    /** 图片 URL 类型标识 */
    public static final String TYPE_IMAGE_URL = "image_url";

    /**
     * 构造文本部件。
     *
     * @param text 文本内容（不能为 null）
     * @return type=text 的部件
     */
    public static ContentPart ofText(String text) {
        if (text == null) {
            throw new IllegalArgumentException("文本部件的 text 不能为空");
        }
        return new ContentPart(TYPE_TEXT, text, null);
    }

    /**
     * 构造图片 URL 部件。
     *
     * @param url 图片可访问的 URL（不能为 null）
     * @return type=image_url 的部件
     */
    public static ContentPart ofImage(String url) {
        if (url == null) {
            throw new IllegalArgumentException("图片部件的 url 不能为空");
        }
        return new ContentPart(TYPE_IMAGE_URL, null, new ImageUrl(url));
    }

    /**
     * 图片 URL 容器（与 OpenAI 协议 {@code image_url:{url:...}} 结构对齐）。
     *
     * @param url 图片可访问的 URL
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ImageUrl(String url) {
    }
}
