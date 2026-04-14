package com.ociworker.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResponseData<T> {
    private int code;
    private String message;
    private T data;

    public static <T> ResponseData<T> ok(T data) {
        return new ResponseData<>(0, "success", data);
    }

    public static <T> ResponseData<T> ok() {
        return new ResponseData<>(0, "success", null);
    }

    public static <T> ResponseData<T> error(String message) {
        return new ResponseData<>(-1, message, null);
    }

    public static <T> ResponseData<T> error(int code, String message) {
        return new ResponseData<>(code, message, null);
    }
}
