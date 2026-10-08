package com.plantarena.feed.adapter.in.web;

import com.plantarena.feed.application.FeedCursorExpiredException;
import com.plantarena.feed.application.FeedCursorInvalidException;
import com.plantarena.shared.web.ApiError;
import com.plantarena.shared.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;


@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FeedExceptionHandler {

    @ExceptionHandler(FeedCursorInvalidException.class)
    public ResponseEntity<ApiError> cursorInvalid(FeedCursorInvalidException e,
                                                  HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "FEED_CURSOR_INVALID", e.getMessage(), request);
    }

    @ExceptionHandler(FeedCursorExpiredException.class)
    public ResponseEntity<ApiError> cursorExpired(FeedCursorExpiredException e,
                                                  HttpServletRequest request) {
        return respond(HttpStatus.GONE, "FEED_CURSOR_EXPIRED", e.getMessage(), request);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String detail,
                                             HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return ResponseEntity.status(status).body(new ApiError(
            URI.create("about:blank"), status.getReasonPhrase(), status.value(), detail,
            URI.create(request.getRequestURI()), code, List.of(), traceId));
    }
}
