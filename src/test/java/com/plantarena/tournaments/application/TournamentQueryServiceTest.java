package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.ListTournamentsUseCase;
import com.plantarena.tournaments.application.support.InMemoryInvitationRepository;
import com.plantarena.tournaments.application.support.InMemoryTagRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Запросы турниров: доступность, фильтры, участники (раздел 13)")
class TournamentQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryInvitationRepository invitations =
        new InMemoryInvitationRepository(tournaments);
    private final InMemoryTournamentEntryRepository entries =
        new InMemoryTournamentEntryRepository(tournaments);
    private final TournamentQueryService service = new TournamentQueryService(tournaments,
        invitations, entries, new TournamentsAccessPolicy());

    private final UUID organizerId = UUID.randomUUID();
    private final CurrentActor organizer = CurrentActor.identified(organizerId, Set.of(AppRole.USER));
    private final CurrentActor admin =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER, AppRole.ADMIN));
    private final CurrentActor stranger =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER));

    private UUID newTournament(UUID tagId, Instant createdAt) {
        Tournament tournament = Tournament.restore(UUID.randomUUID(), organizerId, "Т", null,
            com.plantarena.tournaments.domain.TournamentType.PRIVATE,
            TournamentStatus.REGISTRATION_OPEN,
            com.plantarena.tournaments.domain.EliminationAlgorithmKind.ROUND_ELIMINATION,
            NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2, null,
            tagId == null ? Set.of() : Set.of(tagId), createdAt, 0);
        return tournaments.save(tournament).id();
    }

    @Test
    @DisplayName("список: организатор видит свой, приглашённый — свой, чужой — нет, админ — все")
    void список_доступности() {
        UUID tournamentId = newTournament(null, NOW);
        UUID invitedUserId = UUID.randomUUID();
        invitations.save(Invitation.invite(tournamentId, invitedUserId, organizerId, NOW));

        assertThat(service.list(organizer, null, null, 0, 20).total()).isEqualTo(1);
        assertThat(service.list(CurrentActor.identified(invitedUserId, Set.of(AppRole.USER)),
            null, null, 0, 20).total()).isEqualTo(1);
        assertThat(service.list(stranger, null, null, 0, 20).total()).isZero();
        assertThat(service.list(admin, null, null, 0, 20).total()).isEqualTo(1);
    }

    @Test
    @DisplayName("список: фильтры статуса и тега; неизвестный статус — ошибка")
    void список_фильтры() {
        UUID tagId = UUID.randomUUID();
        newTournament(tagId, NOW);
        Tournament draft = Tournament.createDraft(organizerId, "Черновик", null,
            NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2, Set.of(), NOW.plusSeconds(1));
        tournaments.save(draft);

        assertThat(service.list(organizer, "DRAFT", null, 0, 20).total())
            .isEqualTo(1);
        assertThat(service.list(organizer, null, tagId, 0, 20).total()).isEqualTo(1);
        assertThat(service.list(organizer, "RUNNING", tagId, 0, 20).total())
            .isZero();
        assertThatThrownBy(() -> service.list(organizer, "PAUSED", null, 0, 20))
            .isInstanceOf(UnknownStatusFilterException.class);
    }

    @Test
    @DisplayName("get: приглашённый и участник видят, чужой — 404")
    void get_доступ() {
        UUID tournamentId = newTournament(null, NOW);
        UUID invitedUserId = UUID.randomUUID();
        invitations.save(Invitation.invite(tournamentId, invitedUserId, organizerId, NOW));
        UUID participantId = UUID.randomUUID();
        entries.save(TournamentEntry.admit(tournamentId, participantId, UUID.randomUUID(),
            UUID.randomUUID(), NOW));

        assertThat(service.get(organizer, tournamentId).status()).isEqualTo("REGISTRATION_OPEN");
        assertThat(service.get(CurrentActor.identified(invitedUserId, Set.of(AppRole.USER)),
            tournamentId).id()).isEqualTo(tournamentId);
        assertThat(service.get(CurrentActor.identified(participantId, Set.of(AppRole.USER)),
            tournamentId).id()).isEqualTo(tournamentId);
        assertThatThrownBy(() -> service.get(stranger, tournamentId))
            .isInstanceOf(TournamentNotFoundException.class);
    }

    @Test
    @DisplayName("entries: участник видит список, чужой — 404")
    void entries_доступ() {
        UUID tournamentId = newTournament(null, NOW);
        UUID participantId = UUID.randomUUID();
        entries.save(TournamentEntry.admit(tournamentId, participantId, UUID.randomUUID(),
            UUID.randomUUID(), NOW));

        assertThat(service.list(CurrentActor.identified(participantId, Set.of(AppRole.USER)),
            tournamentId, 0, 20).total()).isEqualTo(1);
        assertThatThrownBy(() -> service.list(stranger, tournamentId, 0, 20))
            .isInstanceOf(TournamentNotFoundException.class);
    }
}
