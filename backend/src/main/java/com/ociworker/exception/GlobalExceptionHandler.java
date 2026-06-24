package com.ociworker.exception;

import com.ociworker.model.vo.ResponseData;
import com.ociworker.util.OciBmcErrorTranslator;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
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

    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbortException(ClientAbortException e) {
        log.debug("Client aborted request: {}", e.getMessage());
    }

    @ExceptionHandler(com.oracle.bmc.model.BmcException.class)
    public ResponseData<?> handleBmcException(com.oracle.bmc.model.BmcException e) {
        String opc = e.getOpcRequestId();
        log.error("OCI API error: status={} opcRequestId={} serviceCode={} message={}",
                e.getStatusCode(), opc != null ? opc : "-", e.getServiceCode(), e.getMessage());
        String friendly = friendlyBmcMessage(e);
        if (StringUtils.hasText(friendly)) {
            return ResponseData.error(friendly);
        }
        String translated = OciBmcErrorTranslator.translate(e);
        if (StringUtils.hasText(translated)) {
            if (StringUtils.hasText(opc) && !translated.contains("opc-request-id")) {
                translated += " (opc-request-id: " + opc + ")";
            }
            return ResponseData.error(translated);
        }
        StringBuilder sb = new StringBuilder("OCI 错误 [").append(e.getStatusCode()).append("]");
        if (StringUtils.hasText(e.getMessage())) {
            sb.append(": ").append(e.getMessage());
        }
        if (StringUtils.hasText(opc)) {
            sb.append(" (opc-request-id: ").append(opc).append(")");
        }
        return ResponseData.error(sb.toString());
    }

    private static String friendlyBmcMessage(com.oracle.bmc.model.BmcException e) {
        String msg = e.getMessage();
        if (!StringUtils.hasText(msg)) {
            return null;
        }
        String serviceCode = e.getServiceCode();
        boolean incorrectState = "IncorrectState".equalsIgnoreCase(serviceCode) || msg.contains("IncorrectState");
        boolean bootVolumeUpdate = msg.contains("UpdateBootVolume operation")
                || msg.contains("BootVolume operation")
                || msg.contains("bootVolumes/");
        boolean waitingForAvailable = msg.contains("UPDATE_PENDING")
                || msg.contains("must be in state AVAILABLE")
                || msg.contains("cannot be resized");
        if (e.getStatusCode() == 409 && incorrectState && bootVolumeUpdate && waitingForAvailable) {
            return "引导卷正在更新中，请等待状态变为 AVAILABLE（可用）后再操作。";
        }
        return null;
    }

    @ExceptionHandler(Exception.class)
    public ResponseData<?> handleException(Exception e) {
        String type = e.getClass().getName();
        String detail = e.getMessage() != null ? e.getMessage() : "(无消息)";
        log.error("Unexpected error: {} | {}", type, detail, e);
        return ResponseData.error("服务器内部错误，请查看日志");
    }
}
