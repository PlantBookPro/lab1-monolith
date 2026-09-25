package com.plantarena.shared.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Тело ошибки ProblemDetail-вида:
 * {type,title,status,detail,instance,code,fieldErrors,traceId,retryAt}
 * (раздел 13 требований; retryAt — срок повтора для 409 IMAGE_RESTRICTED,
 * null у ошибок без срока).
 */
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

    /** Без retryAt — большинство ошибок не имеют срока повтора (совместимость). */
    public ApiError(URI type, String title, int status, String detail, URI instance,
                    String code, List<FieldError> fieldErrors, String traceId) {
        this(type, title, status, detail, instance, code, fieldErrors, traceId, null);
    }

    public ApiError withFieldErrors(List<FieldError> errors) {
        return new ApiError(type, title, status, detail, instance, code, errors, traceId, retryAt);
    }

    /** Срок, когда запрет истечёт (COOLDOWN); null — запрета по сроку нет. */
    public ApiError withRetryAt(Instant newRetryAt) {
        return new ApiError(type, title, status, detail, instance, code, fieldErrors, traceId,
            newRetryAt);
    }
}
