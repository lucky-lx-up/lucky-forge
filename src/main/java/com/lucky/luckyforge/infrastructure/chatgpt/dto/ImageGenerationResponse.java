package com.lucky.luckyforge.infrastructure.chatgpt.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * chatgpt2api 出图响应。
 * <p>data 为生成图列表，每项含 b64_json（Base64 编码图数据）或 url。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImageGenerationResponse(
        List<ImageData> data
) {
    /** 取首张图的 Base64 数据 */
    public String firstBase64() {
        if (data == null || data.isEmpty()) {
            return null;
        }
        return data.get(0).b64Json();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImageData(String b64Json, String url) {
    }
}