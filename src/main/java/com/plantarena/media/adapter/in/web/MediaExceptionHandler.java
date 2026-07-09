package com.plantarena.media.adapter.in.web;

import com.plantarena.media.api.AssetInUseException;
import com.plantarena.media.application.FileTooLargeException;
import com.plantarena.media.application.MediaAssetNotFoundException;
import com.plantarena.media.domain.ImageResolutionTooHighException;
import com.plantarena.media.domain.UnsupportedImageFormatException;
import com.plantarena.shared.web.ApiError;
import com.plantarena.shared.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Перевод исключений media в ProblemDetail-подобное тело (раздел 13:
 * 415 формат, 413 размер файла/пикселей, 404 не найден/скрыт).
 * Живёт в adapter.in.web: shared не зависит от контекстов (правило 10.2.8).
 */
@RestControllerAdvice
public class MediaExceptionHandler {

    @ExceptionHandler(UnsupportedImageFormatException.class)
    public ResponseEntity<ApiError> unsupportedFormat(UnsupportedImageFormatException e,
                                                      HttpServletRequest request) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_IMAGE_FORMAT",
            e.getMessage(), request);
    }

    /** Общий предел 10 MiB: проверка use case и предел Spring multipart — один код. */
    @ExceptionHandler({FileTooLargeException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<ApiError> tooLarge(Exception e, HttpServletRequest request) {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
            "Файл превышает ограничение загрузки 10 MiB", request);
    }

    @ExceptionHandler(ImageResolutionTooHighException.class)
    public ResponseEntity<ApiError> tooManyPixels(ImageResolutionTooHighException e,
                                                  HttpServletRequest request) {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE", e.getMessage(), request);
    }

    @ExceptionHandler(MediaAssetNotFoundException.class)
    public ResponseEntity<ApiError> notFound(MediaAssetNotFoundException e,
                                             HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "MEDIA_ASSET_NOT_FOUND", e.getMessage(), request);
    }

    /** 409: файл задействован растением — сначала архивируйте растение (ADR-008). */
    @ExceptionHandler(AssetInUseException.class)
    public ResponseEntity<ApiError> inUse(AssetInUseException e, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "ASSET_IN_USE", e.getMessage(), request);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String detail,
                                              HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return ResponseEntity.status(status).body(new ApiError(
            URI.create("about:blank"), status.getReasonPhrase(), status.value(), detail,
            URI.create(request.getRequestURI()), code, List.of(), traceId));
    }
}
