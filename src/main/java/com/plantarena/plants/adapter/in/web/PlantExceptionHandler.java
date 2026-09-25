package com.plantarena.plants.adapter.in.web;

import com.plantarena.plants.api.ModerationAlreadyDecidedException;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.application.AssetAlreadyClaimedException;
import com.plantarena.plants.application.AssetNotFoundException;
import com.plantarena.plants.application.ImageRestrictedException;
import com.plantarena.plants.application.PlantUnderReservationException;
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
 * Перевод исключений plants в ProblemDetail-подобное тело (раздел 13:
 * 404 не найдено/скрыто, 409 конфликты задействованности/запретов/резерва).
 * PlantNotEligibleException/ReservationConflictException — контракт api для
 * tournaments (итерация 5), HTTP-перевода не имеют. Живёт в adapter.in.web:
 * shared не зависит от контекстов (правило 10.2.8).
 */
@RestControllerAdvice
public class PlantExceptionHandler {

    @ExceptionHandler(PlantNotFoundException.class)
    public ResponseEntity<ApiError> plantNotFound(PlantNotFoundException e,
                                                  HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "PLANT_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(AssetNotFoundException.class)
    public ResponseEntity<ApiError> assetNotFound(AssetNotFoundException e,
                                                  HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(AssetAlreadyClaimedException.class)
    public ResponseEntity<ApiError> alreadyClaimed(AssetAlreadyClaimedException e,
                                                    HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "ASSET_ALREADY_CLAIMED", e.getMessage(), request);
    }

    /** 409 + retryAt: для COOLDOWN — срок истечения, для PERMANENT — null. */
    @ExceptionHandler(ImageRestrictedException.class)
    public ResponseEntity<ApiError> imageRestricted(ImageRestrictedException e,
                                                    HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
            URI.create("about:blank"), HttpStatus.CONFLICT.getReasonPhrase(),
            HttpStatus.CONFLICT.value(), e.getMessage(),
            URI.create(request.getRequestURI()), "IMAGE_RESTRICTED", List.of(), traceId,
            e.retryAt()));
    }

    @ExceptionHandler(PlantUnderReservationException.class)
    public ResponseEntity<ApiError> underReservation(PlantUnderReservationException e,
                                                      HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "PLANT_UNDER_RESERVATION", e.getMessage(), request);
    }

    @ExceptionHandler(ModerationAlreadyDecidedException.class)
    public ResponseEntity<ApiError> alreadyDecided(ModerationAlreadyDecidedException e,
                                                    HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "MODERATION_ALREADY_DECIDED", e.getMessage(), request);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String detail,
                                              HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return ResponseEntity.status(status).body(new ApiError(
            URI.create("about:blank"), status.getReasonPhrase(), status.value(), detail,
            URI.create(request.getRequestURI()), code, List.of(), traceId));
    }
}
