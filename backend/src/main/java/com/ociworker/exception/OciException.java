package com.ociworker.exception;

import lombok.Getter;

@Getter
public class OciException extends RuntimeException {
    private final int code;

    public OciException(int code, String message) {
        super(message);
        this.code = code;
    }

    public OciException(String message) {
        this(-1, message);
    }
}
