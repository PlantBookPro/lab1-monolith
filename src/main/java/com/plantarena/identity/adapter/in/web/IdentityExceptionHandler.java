package com.plantarena.identity.adapter.in.web;

import com.plantarena.identity.application.EmailAlreadyInUseException;
import com.plantarena.identity.application.UserNotFoundException;
import com.plantarena.shared.web.ApiError;
import com.plantarena.shared.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Перевод исключений identity в ProblemDetail-подобное тело (раздел 13).
 * Живёт в adapter.in.web: shared не зависит от контекстов (правило 10.2.8);
 * Spring выбирает самый специфичный обработчик среди всех advice.
 */
@RestControllerAdvice
public class IdentityExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiError> notFound(UserNotFoundException e, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(EmailAlreadyInUseException.class)
    public ResponseEntity<ApiError> conflict(EmailAlreadyInUseException e, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "EMAIL_ALREADY_IN_USE", e.getMessage(), request);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String detail,
                                             HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return ResponseEntity.status(status).body(new ApiError(
            URI.create("about:blank"), status.getReasonPhrase(), status.value(), detail,
            URI.create(request.getRequestURI()), code, List.of(), traceId));
    }
}
