package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.tournaments.application.port.in.CreateGuestSessionUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@Tag(name = "guest-sessions")
public class GuestSessionController {

    private final CreateGuestSessionUseCase createGuestSession;

    public GuestSessionController(CreateGuestSessionUseCase createGuestSession) {
        this.createGuestSession = createGuestSession;
    }

    @PostMapping("/api/v1/guest-sessions")
    @Operation(operationId = "guest-sessions-create",
        summary = "Создать гостевую сессию: токен возвращается один раз (429 при лимите)")
    public ResponseEntity<GuestSessionResponse> create(HttpServletRequest request) {
        CreateGuestSessionUseCase.GuestSessionIssued issued =
            createGuestSession.issue(request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new GuestSessionResponse(issued.token(), issued.expiresAt()));
    }
}
