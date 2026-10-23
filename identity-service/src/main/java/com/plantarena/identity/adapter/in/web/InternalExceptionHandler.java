package com.plantarena.identity.adapter.in.web;

import com.plantarena.shared.security.NotIdentifiedException;
import com.plantarena.shared.web.ApiError;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = InternalDirectoryController.class)
public class InternalExceptionHandler {

    @ExceptionHandler(NotIdentifiedException.class)
    public ResponseEntity<ApiError> notIdentified(NotIdentifiedException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(
            URI.create("about:blank"), "Not Found", 404,
            "Актёр не разрешён: " + e.getMessage(), URI.create("/internal/actors"),
            "ACTOR_NOT_FOUND", List.of(), UUID.randomUUID().toString()));
    }
}
