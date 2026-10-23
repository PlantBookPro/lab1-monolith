package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.webflux.Blocking;
import com.plantarena.tournaments.application.port.in.CreateGuestSessionUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;


@RestController
@Tag(name = "guest-sessions")
@RequiredArgsConstructor
public class GuestSessionController {

    private final CreateGuestSessionUseCase createGuestSession;

    @PostMapping("/api/v1/guest-sessions")
    @Operation(operationId = "guest-sessions-create",
        summary = "Создать гостевую сессию: токен возвращается один раз (429 при лимите)")
    public Mono<ResponseEntity<GuestSessionResponse>> create(ServerHttpRequest request) {
        String remoteAddress = request.getRemoteAddress() == null ? null
            : request.getRemoteAddress().getAddress().getHostAddress();
        return Blocking.call(() -> createGuestSession.issue(remoteAddress))
            .map(issued -> ResponseEntity.status(HttpStatus.CREATED)
                .body(new GuestSessionResponse(issued.token(), issued.expiresAt())));
    }
}
