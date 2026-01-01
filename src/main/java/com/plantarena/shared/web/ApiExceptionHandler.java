package com.plantarena.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Переводит исключения в ProblemDetail-подобное тело ApiError.
 * Доменные исключения контекстов добавляются сюда в итерациях 1–9;
 * они не знают об HTTP (раздел 13 требований).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> notFound(NoResourceFoundException e, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
            "Ресурс не найден: " + request.getRequestURI(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_BODY",
            "Некорректное тело запроса", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
            .map(fieldError -> new ApiError.FieldError(fieldError.getField(), fieldError.getDefaultMessage()))
            .toList();
        ApiError error = error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
            "Некорректные поля запроса", request).withFieldErrors(fieldErrors);
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest request) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "Внутренняя ошибка сервера", request);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String detail,
                                             HttpServletRequest request) {
        return ResponseEntity.status(status)
            .body(error(status, code, detail, request));
    }

    private ApiError error(HttpStatus status, String code, String detail, HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return new ApiError(
            URI.create("about:blank"),
            status.getReasonPhrase(),
            status.value(),
            detail,
            URI.create(request.getRequestURI()),
            code,
            List.of(),
            traceId);
    }
}
