package com.lucky.luckyforge.common.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应体。
 * <p>所有 Controller 方法返回 {@code ResponseEntity<ApiResponse<T>>}，body 为本类型实例。
 * 字段语义对齐 AGENTS.md 契约：{@code result} 取 {@link #SUCCESS} 或 {@link #ERROR}，
 * {@code message} 为人类可读信息，{@code data} 为业务数据（失败时为 null）。
 *
 * <p>静态工厂 {@link #success(Object)} / {@link #error(String)} 统一构造实例，
 * 修正 AGENTS.md 契约示例中 {@code ApiResponse.error(400, msg)} 与字段签名不符的偏差。
 *
 * @param <T> 业务数据类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    /** 成功标识（result 字段取值之一） */
    public static final String SUCCESS = "SUCCESS";
    /** 失败标识（result 字段取值之一） */
    public static final String ERROR = "ERROR";

    /** 结果标识：SUCCESS / ERROR */
    private String result;

    /** 成功或错误信息 */
    private String message;

    /** 成功时由 Service 返回的数据，失败时为 null */
    private T data;

    /**
     * 构造成功响应（默认 message）。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return result=SUCCESS 的响应实例
     */
    public static <T> ApiResponse<T> success(T data) {
        return success("OK", data);
    }

    /**
     * 构造成功响应（自定义 message）。
     *
     * @param message 成功信息
     * @param data    业务数据
     * @param <T>     数据类型
     * @return result=SUCCESS 的响应实例
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(SUCCESS, message, data);
    }

    /**
     * 构造失败响应。
     *
     * @param message 错误信息
     * @param <T>     数据类型
     * @return result=ERROR 的响应实例
     */
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(ERROR, message, null);
    }
}
