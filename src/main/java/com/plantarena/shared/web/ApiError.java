package com.plantarena.shared.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;


public record ApiError(
        URI type,
        String title,
        int status,
        String detail,
        URI instance,
        String code,
        List<FieldError> fieldErrors,
        String traceId,
        Instant retryAt) {

    public record FieldError(String field, String message) {
    }

    
    public ApiError(URI type, String title, int status, String detail, URI instance,
                    String code, List<FieldError> fieldErrors, String traceId) {
        this(type, title, status, detail, instance, code, fieldErrors, traceId, null);
    }

    public ApiError withFieldErrors(List<FieldError> errors) {
        return new ApiError(type, title, status, detail, instance, code, errors, traceId, retryAt);
    }

    
    public ApiError withRetryAt(Instant newRetryAt) {
        return new ApiError(type, title, status, detail, instance, code, fieldErrors, traceId,
            newRetryAt);
    }
}
