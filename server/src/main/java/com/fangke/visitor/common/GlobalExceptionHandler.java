package com.fangke.visitor.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：所有 /api/** 异常统一转成 {@link ApiResponse}。
 *
 * <p>设备回调接口不走这里（它们返回设备自己的格式，异常也在回调内自行兜底）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex, HttpServletRequest request) {
        log.warn("业务异常 uri={} code={} msg={}", request.getRequestURI(), ex.getCode(), ex.getMessage());
        HttpStatus status = httpStatusOf(ex.getCode());
        return ResponseEntity.status(status).body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
    }

    /**
     * 设备通道异常（4xxx）：以前落进兜底分支变成 5001「系统内部错误」，
     * 现场分不清是设备离线、密码错还是能力不支持，这里显式映射。
     */
    @ExceptionHandler(com.fangke.visitor.device.adapter.DeviceException.class)
    public ResponseEntity<ApiResponse<Void>> handleDevice(
            com.fangke.visitor.device.adapter.DeviceException ex, HttpServletRequest request) {
        log.warn("设备异常 uri={} code={} msg={}", request.getRequestURI(), ex.getCode(), ex.getMessage());
        HttpStatus status = httpStatusOf(ex.getCode());
        return ResponseEntity.status(status).body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        FieldError first = ex.getBindingResult().getFieldError();
        String message = first == null
                ? ErrorCode.PARAM_INVALID.message()
                : first.getField() + ": " + first.getDefaultMessage();
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_INVALID.code(), message));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID.code(), "请求体不是合法 JSON"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("未处理异常 uri={}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR.code(), ErrorCode.INTERNAL_ERROR.message()));
    }

    /** 错误码 → HTTP 状态码映射，见 docs/11 §0.7。 */
    private HttpStatus httpStatusOf(int code) {
        int segment = code / 1000;
        return switch (segment) {
            case 1 -> HttpStatus.BAD_REQUEST;
            case 2 -> code == 2001 ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
            case 3 -> switch (code) {
                case 3008, 3009 -> HttpStatus.UNAUTHORIZED;
                case 3003, 3010, 3006 -> HttpStatus.FORBIDDEN;
                default -> HttpStatus.CONFLICT;
            };
            case 4 -> switch (code) {
                case 4001, 4004 -> HttpStatus.SERVICE_UNAVAILABLE;
                case 4005 -> HttpStatus.INSUFFICIENT_STORAGE;
                case 4006 -> HttpStatus.NOT_IMPLEMENTED;
                default -> HttpStatus.BAD_GATEWAY;
            };
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
