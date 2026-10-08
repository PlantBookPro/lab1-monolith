package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import com.plantarena.shared.web.PaginationParams;
import com.plantarena.tournaments.api.TournamentData;
import com.plantarena.tournaments.application.port.in.CancelTournamentUseCase;
import com.plantarena.tournaments.application.port.in.CreateTournamentUseCase;
import com.plantarena.tournaments.application.port.in.DeleteTournamentUseCase;
import com.plantarena.tournaments.application.port.in.GetLeaderboardUseCase;
import com.plantarena.tournaments.application.port.in.GetTournamentUseCase;
import com.plantarena.tournaments.application.port.in.InviteUserUseCase;
import com.plantarena.tournaments.application.port.in.ListEntriesUseCase;
import com.plantarena.tournaments.application.port.in.ListInvitationsUseCase;
import com.plantarena.tournaments.application.port.in.ListResultsUseCase;
import com.plantarena.tournaments.application.port.in.ListRoundsUseCase;
import com.plantarena.tournaments.application.port.in.ListTournamentsUseCase;
import com.plantarena.tournaments.application.port.in.OpenRegistrationUseCase;
import com.plantarena.tournaments.application.port.in.RevokeInvitationUseCase;
import com.plantarena.tournaments.application.port.in.StartTournamentUseCase;
import com.plantarena.tournaments.application.port.in.UpdateTournamentUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/tournaments")
@Tag(name = "tournaments")
public class TournamentController {

    private final CreateTournamentUseCase createTournament;
    private final UpdateTournamentUseCase updateTournament;
    private final DeleteTournamentUseCase deleteTournament;
    private final OpenRegistrationUseCase openRegistration;
    private final CancelTournamentUseCase cancelTournament;
    private final StartTournamentUseCase startTournament;
    private final ListTournamentsUseCase listTournaments;
    private final GetTournamentUseCase getTournament;
    private final ListEntriesUseCase listEntries;
    private final InviteUserUseCase inviteUser;
    private final RevokeInvitationUseCase revokeInvitation;
    private final ListInvitationsUseCase listInvitations;
    private final ListRoundsUseCase listRounds;
    private final GetLeaderboardUseCase getLeaderboard;
    private final ListResultsUseCase listResults;
    private final CurrentActorProvider currentActorProvider;

    public TournamentController(CreateTournamentUseCase createTournament,
                                UpdateTournamentUseCase updateTournament,
                                DeleteTournamentUseCase deleteTournament,
                                OpenRegistrationUseCase openRegistration,
                                CancelTournamentUseCase cancelTournament,
                                StartTournamentUseCase startTournament,
                                ListTournamentsUseCase listTournaments,
                                GetTournamentUseCase getTournament,
                                ListEntriesUseCase listEntries,
                                InviteUserUseCase inviteUser,
                                RevokeInvitationUseCase revokeInvitation,
                                ListInvitationsUseCase listInvitations,
                                ListRoundsUseCase listRounds,
                                GetLeaderboardUseCase getLeaderboard,
                                ListResultsUseCase listResults,
                                CurrentActorProvider currentActorProvider) {
        this.createTournament = createTournament;
        this.updateTournament = updateTournament;
        this.deleteTournament = deleteTournament;
        this.openRegistration = openRegistration;
        this.cancelTournament = cancelTournament;
        this.startTournament = startTournament;
        this.listTournaments = listTournaments;
        this.getTournament = getTournament;
        this.listEntries = listEntries;
        this.inviteUser = inviteUser;
        this.revokeInvitation = revokeInvitation;
        this.listInvitations = listInvitations;
        this.listRounds = listRounds;
        this.getLeaderboard = getLeaderboard;
        this.listResults = listResults;
        this.currentActorProvider = currentActorProvider;
    }

    @PostMapping
    @Operation(operationId = "tournaments-create",
        summary = "Создать PRIVATE DRAFT (модератор/админ)")
    public ResponseEntity<TournamentResponse> create(
            @Valid @RequestBody CreateTournamentRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        TournamentData tournament = createTournament.create(actor,
            new CreateTournamentUseCase.CreateTournamentCommand(request.name(),
                request.description(), request.registrationDeadline(),
                Duration.ofSeconds(request.roundDurationSeconds()),
                request.eliminationFraction(), request.minParticipants(),
                request.tagIds() == null ? Set.of() : request.tagIds()));
        return ResponseEntity
            .created(URI.create("/api/v1/tournaments/" + tournament.id()))
            .body(TournamentResponse.from(tournament));
    }

    @GetMapping
    @Operation(operationId = "tournaments-list",
        summary = "Список доступных турниров (фильтры status/tag, X-Total-Count)")
    public ResponseEntity<List<TournamentResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID tagId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        ListTournamentsUseCase.TournamentListResult result =
            listTournaments.list(actor, status, tagId, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(result.items().stream().map(TournamentResponse::from).toList());
    }

    @GetMapping("/{id}")
    @Operation(operationId = "tournaments-get",
        summary = "Турнир (организатор/админ/приглашённый/участник; скрытый — 404)")
    public TournamentResponse get(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return TournamentResponse.from(getTournament.get(actor, id));
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "tournaments-update",
        summary = "Изменить турнир (параметры — только DRAFT, описание — безопасное)")
    public TournamentResponse update(@PathVariable UUID id,
                                     @Valid @RequestBody UpdateTournamentRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        return TournamentResponse.from(updateTournament.update(actor, id,
            new UpdateTournamentUseCase.UpdateTournamentCommand(request.name(),
                request.description(), request.registrationDeadline(),
                request.roundDurationSeconds() == null ? null
                    : Duration.ofSeconds(request.roundDurationSeconds()),
                request.eliminationFraction(), request.minParticipants(), request.tagIds())));
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "tournaments-delete",
        summary = "Удалить пустой DRAFT (иначе 409)")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        deleteTournament.delete(actor, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/open-registration")
    @Operation(operationId = "tournaments-open-registration",
        summary = "Открыть приём заявок (DRAFT → REGISTRATION_OPEN)")
    public TournamentResponse openRegistration(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return TournamentResponse.from(openRegistration.openRegistration(actor, id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(operationId = "tournaments-cancel",
        summary = "Отменить турнир до RUNNING с освобождением резервов")
    public TournamentResponse cancel(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return TournamentResponse.from(cancelTournament.cancel(actor, id));
    }

    @PostMapping("/{id}/start")
    @Operation(operationId = "tournaments-start",
        summary = "Стартовать вручную (тот же use case, что scheduler; только после дедлайна)")
    public TournamentResponse start(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return TournamentResponse.from(startTournament.start(actor, id));
    }

    @PostMapping("/{id}/invitations")
    @Operation(operationId = "tournaments-invite-user",
        summary = "Пригласить пользователя (организатор, до дедлайна; дубль пары — 409)")
    public ResponseEntity<InvitationResponse> invite(@PathVariable UUID id,
                                                     @Valid @RequestBody InviteUserRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        var invitation = inviteUser.invite(actor, id, request.userId());
        return ResponseEntity
            .created(URI.create("/api/v1/tournaments/" + id + "/invitations/"
                + invitation.id()))
            .body(InvitationResponse.from(invitation));
    }

    @GetMapping("/{id}/invitations")
    @Operation(operationId = "tournaments-list-invitations",
        summary = "Приглашения турнира (организатор/админ, X-Total-Count)")
    public ResponseEntity<List<InvitationResponse>> listInvitations(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        ListInvitationsUseCase.InvitationListResult result =
            listInvitations.list(actor, id, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(result.items().stream().map(InvitationResponse::from).toList());
    }

    @DeleteMapping("/{id}/invitations/{invitationId}")
    @Operation(operationId = "tournaments-revoke-invitation",
        summary = "Отозвать не принятое приглашение (REVOKED, история сохраняется)")
    public ResponseEntity<Void> revokeInvitation(@PathVariable UUID id,
                                                 @PathVariable UUID invitationId) {
        CurrentActor actor = currentActorProvider.currentActor();
        revokeInvitation.revoke(actor, id, invitationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/entries")
    @Operation(operationId = "tournaments-list-entries",
        summary = "Участники/результаты турнира (имеющие доступ, X-Total-Count)")
    public ResponseEntity<List<EntryResponse>> listEntries(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        ListEntriesUseCase.EntryListResult result =
            listEntries.list(actor, id, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(result.items().stream().map(EntryResponse::from).toList());
    }

    @GetMapping("/{id}/rounds")
    @Operation(operationId = "tournaments-list-rounds",
        summary = "История раундов турнира (X-Total-Count)")
    public ResponseEntity<List<RoundResponse>> rounds(@PathVariable UUID id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        ListRoundsUseCase.RoundListResult result =
            listRounds.listRounds(actor, id, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(result.items().stream().map(RoundResponse::from).toList());
    }

    @GetMapping("/{id}/leaderboard")
    @Operation(operationId = "tournaments-get-leaderboard",
        summary = "Счёт окна (без windowId — текущее/последнее; X-Total-Count)")
    public ResponseEntity<LeaderboardResponse> leaderboard(@PathVariable UUID id,
            @RequestParam(required = false) UUID windowId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        GetLeaderboardUseCase.LeaderboardResult result =
            getLeaderboard.get(actor, id, windowId, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(LeaderboardResponse.from(result));
    }

    @GetMapping("/{id}/results")
    @Operation(operationId = "tournaments-list-results",
        summary = "Итоги: победитель и выбывшие (X-Total-Count)")
    public ResponseEntity<ResultResponse> results(@PathVariable UUID id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        ListResultsUseCase.ResultListResult result =
            listResults.listResults(actor, id, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(ResultResponse.from(result));
    }
}
