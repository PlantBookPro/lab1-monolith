package com.plantarena.shared.web;

import java.net.URI;
import java.util.List;

/**
 * Тело ошибки ProblemDetail-вида:
 * {type,title,status,detail,instance,code,fieldErrors,traceId} (раздел 13 требований).
 */
public record ApiError(
        URI type,
        String title,
        int status,
        String detail,
        URI instance,
        String code,
        List<FieldError> fieldErrors,
        String traceId) {

    public record FieldError(String field, String message) {
    }

    public ApiError withFieldErrors(List<FieldError> errors) {
        return new ApiError(type, title, status, detail, instance, code, errors, traceId);
    }
}
