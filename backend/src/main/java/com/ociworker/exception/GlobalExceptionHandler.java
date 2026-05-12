package com.ociworker.exception;

import com.ociworker.model.vo.ResponseData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
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

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseData<?> handleNoResourceFound(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return ResponseData.error(404, "资源不存在");
    }

    @ExceptionHandler(com.oracle.bmc.model.BmcException.class)
    public ResponseData<?> handleBmcException(com.oracle.bmc.model.BmcException e) {
        String opc = e.getOpcRequestId();
        log.error("OCI API error: status={} opcRequestId={} serviceCode={} message={}",
                e.getStatusCode(), opc != null ? opc : "-", e.getServiceCode(), e.getMessage());
        StringBuilder sb = new StringBuilder("OCI 错误 [").append(e.getStatusCode()).append("]");
        if (StringUtils.hasText(e.getMessage())) {
            sb.append(": ").append(e.getMessage());
        }
        if (StringUtils.hasText(opc)) {
            sb.append(" (opc-request-id: ").append(opc).append(")");
        }
        return ResponseData.error(sb.toString());
    }

    @ExceptionHandler(Exception.class)
    public ResponseData<?> handleException(Exception e) {
        String type = e.getClass().getName();
        String detail = e.getMessage() != null ? e.getMessage() : "(无消息)";
        log.error("Unexpected error: {} | {}", type, detail, e);
        return ResponseData.error("服务器内部错误，请查看日志");
    }
}
