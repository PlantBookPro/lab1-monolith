package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.web.PaginationParams;
import com.plantarena.shared.webflux.Blocking;
import com.plantarena.shared.webflux.ReactiveCurrentActorProvider;
import com.plantarena.tournaments.application.port.in.AcceptInvitationUseCase;
import com.plantarena.tournaments.application.port.in.DeclineInvitationUseCase;
import com.plantarena.tournaments.application.port.in.ListInvitationsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;


@RestController
@Tag(name = "tournaments")
@RequiredArgsConstructor
public class InvitationController {

    private final AcceptInvitationUseCase acceptInvitation;
    private final DeclineInvitationUseCase declineInvitation;
    private final ListInvitationsUseCase listInvitations;
    private final ReactiveCurrentActorProvider actors;

    @GetMapping("/api/v1/me/invitations")
    @Operation(operationId = "invitations-list-mine",
        summary = "Свои приглашения (X-Total-Count)")
    public Mono<ResponseEntity<List<InvitationResponse>>> listMine(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return actors.currentActor().flatMap(actor -> Blocking.call(() -> {
                PaginationParams pagination = PaginationParams.of(page, size);
                ListInvitationsUseCase.InvitationListResult result =
                    listInvitations.listMine(actor, pagination.page(), pagination.size());
                return ResponseEntity.ok()
                    .header("X-Total-Count", String.valueOf(result.total()))
                    .body(result.items().stream().map(InvitationResponse::from).toList());
            }));
    }

    @PostMapping("/api/v1/invitations/{id}/accept")
    @Operation(operationId = "invitations-accept",
        summary = "Принять приглашение со своим растением (до дедлайна; резерв в той же tx)")
    public Mono<InvitationResponse> accept(@PathVariable UUID id,
                                            @Valid @RequestBody AcceptInvitationRequest request) {
        return actors.currentActor()
            .flatMap(actor -> Blocking.call(() -> acceptInvitation.accept(actor, id, request.plantId())))
            .map(InvitationResponse::from);
    }

    @PostMapping("/api/v1/invitations/{id}/decline")
    @Operation(operationId = "invitations-decline",
        summary = "Отказаться от приглашения до старта (резерв освобождается)")
    public Mono<InvitationResponse> decline(@PathVariable UUID id) {
        return actors.currentActor()
            .flatMap(actor -> Blocking.call(() -> declineInvitation.decline(actor, id)))
            .map(InvitationResponse::from);
    }
}
