package com.learning.order_service.exception;

import com.learning.order_service.common.StatusCode;
import com.learning.order_service.web.response.ErrorResponse;
import com.learning.order_service.web.response.Response;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Converts exceptions to the standard error envelope used by the other services.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<ErrorResponse>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> errors.put(
                error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName(),
                error.getDefaultMessage()));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", errors);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    public ResponseEntity<Response<ErrorResponse>> handleParameterValidation(Exception ex) {
        log.warn("Parameter validation error: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request parameter validation failed", null);
    }

    /** The gateway sets X-User-Id after validating the JWT; without it the caller is unauthenticated. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Response<ErrorResponse>> handleMissingHeader(MissingRequestHeaderException ex) {
        if ("X-User-Id".equalsIgnoreCase(ex.getHeaderName())) {
            return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing caller identity", null);
        }
        if ("Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())) {
            return build(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "The Idempotency-Key header is required", null);
        }
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), null);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Response<ErrorResponse>> handleBadRequest(Exception ex) {
        log.warn("Malformed request: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Malformed request: check body, parameters and headers", null);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Response<ErrorResponse>> handleNotFound(RuntimeException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), null);
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    public ResponseEntity<Response<ErrorResponse>> handleBusinessRule(BusinessRuleViolationException ex) {
        log.warn("Business rule violation: {} {}", ex.getErrorCode(), ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_CONTENT, ex.getErrorCode(), ex.getMessage(), null);
    }

    @ExceptionHandler(DependencyUnavailableException.class)
    public ResponseEntity<Response<ErrorResponse>> handleDependency(DependencyUnavailableException ex) {
        log.error("Dependency unavailable: {}", ex.getMessage(), ex.getCause());
        return build(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", ex.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Response<ErrorResponse>> handleUnexpected(Exception ex) {
        if (ex instanceof org.springframework.web.ErrorResponse springError) {
            HttpStatusCode status = springError.getStatusCode();
            return build(status, "REQUEST_REJECTED", ex.getMessage(), null);
        }
        log.error("Unexpected error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL",
                "An unexpected error occurred. Please contact support.", null);
    }

    private ResponseEntity<Response<ErrorResponse>> build(HttpStatusCode status, String errorCode,
                                                         String message, Map<String, String> details) {
        var error = new ErrorResponse(errorCode, message, status.value(), OffsetDateTime.now(ZoneOffset.UTC), details);
        return ResponseEntity.status(status).body(new Response<>(StatusCode.FAILURE, message, error));
    }
}
