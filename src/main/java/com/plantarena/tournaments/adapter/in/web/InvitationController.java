package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import com.plantarena.shared.web.PaginationParams;
import com.plantarena.tournaments.application.port.in.AcceptInvitationUseCase;
import com.plantarena.tournaments.application.port.in.DeclineInvitationUseCase;
import com.plantarena.tournaments.application.port.in.ListInvitationsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@Tag(name = "tournaments")
public class InvitationController {

    private final AcceptInvitationUseCase acceptInvitation;
    private final DeclineInvitationUseCase declineInvitation;
    private final ListInvitationsUseCase listInvitations;
    private final CurrentActorProvider currentActorProvider;

    public InvitationController(AcceptInvitationUseCase acceptInvitation,
                                DeclineInvitationUseCase declineInvitation,
                                ListInvitationsUseCase listInvitations,
                                CurrentActorProvider currentActorProvider) {
        this.acceptInvitation = acceptInvitation;
        this.declineInvitation = declineInvitation;
        this.listInvitations = listInvitations;
        this.currentActorProvider = currentActorProvider;
    }

    @GetMapping("/api/v1/me/invitations")
    @Operation(operationId = "invitations-list-mine",
        summary = "Свои приглашения (X-Total-Count)")
    public ResponseEntity<List<InvitationResponse>> listMine(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        ListInvitationsUseCase.InvitationListResult result =
            listInvitations.listMine(actor, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(result.items().stream().map(InvitationResponse::from).toList());
    }

    @PostMapping("/api/v1/invitations/{id}/accept")
    @Operation(operationId = "invitations-accept",
        summary = "Принять приглашение со своим растением (до дедлайна; резерв в той же tx)")
    public InvitationResponse accept(@PathVariable UUID id,
                                     @Valid @RequestBody AcceptInvitationRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        return InvitationResponse.from(acceptInvitation.accept(actor, id, request.plantId()));
    }

    @PostMapping("/api/v1/invitations/{id}/decline")
    @Operation(operationId = "invitations-decline",
        summary = "Отказаться от приглашения до старта (резерв освобождается)")
    public InvitationResponse decline(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return InvitationResponse.from(declineInvitation.decline(actor, id));
    }
}
