package com.ociworker.exception;

import com.ociworker.model.vo.ResponseData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OciException.class)
    public ResponseData<?> handleOciException(OciException e) {
        log.error("Business error: {}", e.getMessage());
        return ResponseData.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseData<?> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        return ResponseData.error(message);
    }

    @ExceptionHandler(Exception.class)
    public ResponseData<?> handleException(Exception e) {
        log.error("Unexpected error: ", e);
        return ResponseData.error("Internal server error: " + e.getMessage());
    }
}
