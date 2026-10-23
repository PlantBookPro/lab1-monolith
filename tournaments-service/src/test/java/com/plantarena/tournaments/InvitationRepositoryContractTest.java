package com.plantarena.tournaments;

import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Контракт InvitationRepository: уникальность пары (tournamentId, userId)
 * обеспечивает и фейк, и UNIQUE-индекс PostgreSQL (раздел 11).
 */
@DisplayName("Контракт InvitationRepository")
@Transactional
public abstract class InvitationRepositoryContractTest {

    protected static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    protected abstract InvitationRepository repository();

    protected abstract TournamentRepository tournaments();

    private UUID newTournamentId() {
        Tournament tournament = Tournament.createDraft(UUID.randomUUID(), "Т", null,
            NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2, Set.of(), NOW);
        return tournaments().save(tournament).id();
    }

    @Test
    @DisplayName("save/findById: roundtrip всех полей, включая заявку")
    void roundtrip() {
        UUID tournamentId = newTournamentId();
        UUID userId = UUID.randomUUID();
        Invitation invitation = Invitation.invite(tournamentId, userId,
            UUID.randomUUID(), NOW);
        invitation.accept(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false, NOW);
        repository().save(invitation);

        Invitation loaded = repository().findById(invitation.id()).orElseThrow();
        assertThat(loaded.tournamentId()).isEqualTo(tournamentId);
        assertThat(loaded.userId()).isEqualTo(userId);
        assertThat(loaded.status()).isEqualTo(InvitationStatus.ACCEPTED_PENDING_MODERATION);
        assertThat(loaded.submittedPlantId()).isEqualTo(invitation.submittedPlantId());
        assertThat(loaded.reservationId()).isEqualTo(invitation.reservationId());
        assertThat(loaded.submissionKey()).isEqualTo(invitation.submissionKey());
        assertThat(loaded.respondedAt()).isEqualTo(NOW);
        assertThat(loaded.version()).isEqualTo(invitation.version());
    }

    @Test
    @DisplayName("уникальность пары (tournamentId, userId) — как БД")
    void уникальность_пары() {
        UUID tournamentId = newTournamentId();
        UUID userId = UUID.randomUUID();
        repository().save(Invitation.invite(tournamentId, userId, UUID.randomUUID(), NOW));

        assertThatThrownBy(() -> repository().save(
            Invitation.invite(tournamentId, userId, UUID.randomUUID(), NOW)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("поиски: по паре, по растению и статусу, по турниру/статусу, exists")
    void поиски() {
        UUID tournamentId = newTournamentId();
        UUID userId = UUID.randomUUID();
        Invitation invitation = repository().save(
            Invitation.invite(tournamentId, userId, UUID.randomUUID(), NOW));
        Invitation other = repository().save(Invitation.invite(tournamentId,
            UUID.randomUUID(), UUID.randomUUID(), NOW));
        Invitation inOtherTournament = repository().save(Invitation.invite(newTournamentId(),
            UUID.randomUUID(), UUID.randomUUID(), NOW));

        assertThat(repository().findByTournamentIdAndUserId(tournamentId, userId))
            .contains(invitation);
        assertThat(repository().findByTournamentIdAndUserId(tournamentId, UUID.randomUUID()))
            .isEmpty();

        UUID plantId = UUID.randomUUID();
        Invitation accepted = Invitation.invite(inOtherTournament.tournamentId(),
            UUID.randomUUID(), UUID.randomUUID(), NOW);
        accepted.accept(plantId, UUID.randomUUID(), UUID.randomUUID(), false, NOW);
        repository().save(accepted);
        assertThat(repository().findBySubmittedPlantIdAndStatus(plantId,
            InvitationStatus.ACCEPTED_PENDING_MODERATION)).contains(accepted);
        assertThat(repository().findBySubmittedPlantIdAndStatus(plantId,
            InvitationStatus.INVITED)).isEmpty();

        assertThat(repository().findByTournamentIdAndStatus(tournamentId,
            InvitationStatus.INVITED)).extracting(Invitation::id)
            .containsExactlyInAnyOrder(invitation.id(), other.id());
        assertThat(repository().existsByTournamentId(tournamentId)).isTrue();
        assertThat(repository().existsByTournamentId(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("списки с пагинацией и count: по турниру и по пользователю")
    void списки() {
        UUID tournamentId = newTournamentId();
        UUID userId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            repository().save(Invitation.invite(tournamentId, UUID.randomUUID(),
                UUID.randomUUID(), NOW));
        }
        repository().save(Invitation.invite(newTournamentId(), userId,
            UUID.randomUUID(), NOW));

        assertThat(repository().findByTournamentId(tournamentId, 0, 2)).hasSize(2);
        assertThat(repository().countByTournamentId(tournamentId)).isEqualTo(3);
        assertThat(repository().findByUserId(userId, 0, 20)).hasSize(1);
        assertThat(repository().countByUserId(userId)).isEqualTo(1);
    }
}
