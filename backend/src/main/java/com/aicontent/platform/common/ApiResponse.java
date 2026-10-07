package com.aicontent.platform.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Common envelope: {@code {"success":..., "data":..., "error":...}}. */
public record ApiResponse<T>(boolean success, T data, ErrorBody error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> fail(String code, String message) {
        return new ApiResponse<>(false, null, new ErrorBody(code, message, null));
    }

    public static ApiResponse<Void> fail(String code, String message, Object details) {
        return new ApiResponse<>(false, null, new ErrorBody(code, message, details));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message, Object details) {}
}
