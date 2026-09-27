package com.plantarena.tournaments;

import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
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

/** Контракт TournamentEntryRepository: уникальность (tournamentId, userId). */
@DisplayName("Контракт TournamentEntryRepository")
@Transactional
public abstract class TournamentEntryRepositoryContractTest {

    protected static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    protected abstract TournamentEntryRepository repository();

    protected abstract TournamentRepository tournaments();

    private UUID newTournamentId() {
        Tournament tournament = Tournament.createDraft(UUID.randomUUID(), "Т", null,
            NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2, Set.of(), NOW);
        return tournaments().save(tournament).id();
    }

    @Test
    @DisplayName("save + списки + exists")
    void save_и_поиски() {
        UUID tournamentId = newTournamentId();
        UUID userId = UUID.randomUUID();
        TournamentEntry entry = TournamentEntry.admit(tournamentId, userId,
            UUID.randomUUID(), UUID.randomUUID(), NOW);
        repository().save(entry);
        repository().save(TournamentEntry.admit(tournamentId, UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), NOW));

        assertThat(repository().findByTournamentId(tournamentId, 0, 1)).hasSize(1);
        assertThat(repository().countByTournamentId(tournamentId)).isEqualTo(2);
        assertThat(repository().existsByTournamentIdAndUserId(tournamentId, userId)).isTrue();
        assertThat(repository().existsByTournamentIdAndUserId(tournamentId,
            UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("уникальность пары (tournamentId, userId) — как БД")
    void уникальность_пары() {
        UUID tournamentId = newTournamentId();
        UUID userId = UUID.randomUUID();
        repository().save(TournamentEntry.admit(tournamentId, userId, UUID.randomUUID(),
            UUID.randomUUID(), NOW));

        assertThatThrownBy(() -> repository().save(
            TournamentEntry.admit(tournamentId, userId, UUID.randomUUID(),
                UUID.randomUUID(), NOW)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
