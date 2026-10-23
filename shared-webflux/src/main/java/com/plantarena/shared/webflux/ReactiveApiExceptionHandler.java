package com.plantarena.shared.webflux;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.NotIdentifiedException;
import com.plantarena.shared.web.ApiError;
import com.plantarena.shared.web.InvalidPaginationException;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

@RestControllerAdvice
public class ReactiveApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> status(ResponseStatusException e, ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        return respond(status, "RESOURCE_NOT_FOUND".equals(status.name()) ? "RESOURCE_NOT_FOUND" : "HTTP_ERROR",
            e.getReason(), exchange);
    }

    @ExceptionHandler(NotIdentifiedException.class)
    public ResponseEntity<ApiError> notIdentified(NotIdentifiedException e, ServerWebExchange exchange) {
        return respond(HttpStatus.UNAUTHORIZED, "NOT_IDENTIFIED", e.getMessage(), exchange);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> accessDenied(AccessDeniedException e, ServerWebExchange exchange) {
        return respond(HttpStatus.FORBIDDEN, "ACCESS_DENIED", e.getMessage(), exchange);
    }

    @ExceptionHandler(InvalidPaginationException.class)
    public ResponseEntity<ApiError> invalidPagination(InvalidPaginationException e, ServerWebExchange exchange) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", e.getMessage(), exchange);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e, ServerWebExchange exchange) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_BODY", "Некорректное тело запроса", exchange);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e, ServerWebExchange exchange) {
        List<ApiError.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
            .map(f -> new ApiError.FieldError(f.getField(), f.getDefaultMessage()))
            .toList();
        ApiError error = error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Некорректные поля запроса", exchange)
            .withFieldErrors(fieldErrors);
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> invalidParameter(MethodArgumentTypeMismatchException e, ServerWebExchange exchange) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
            "Некорректное значение параметра: " + e.getName(), exchange);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e, ServerWebExchange exchange) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Внутренняя ошибка сервера", exchange);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String detail,
                                             ServerWebExchange exchange) {
        return ResponseEntity.status(status).body(error(status, code, detail, exchange));
    }

    private ApiError error(HttpStatus status, String code, String detail, ServerWebExchange exchange) {
        String traceId = (String) exchange.getAttribute(TraceIdWebFilter.TRACE_ID_ATTRIBUTE);
        return new ApiError(URI.create("about:blank"), status.getReasonPhrase(), status.value(), detail,
            exchange.getRequest().getURI(), code, List.of(), traceId);
    }
}
