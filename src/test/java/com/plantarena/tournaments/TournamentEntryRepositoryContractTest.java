package com.plantarena.tournaments;

import com.plantarena.tournaments.domain.EntryStatus;
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
    @DisplayName("findById возвращает сохранённое участие")
    void findById_возвращает_сохранённое_участие() {
        UUID tournamentId = newTournamentId();
        TournamentEntry entry = TournamentEntry.admit(tournamentId, UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), NOW);
        repository().save(entry);

        assertThat(repository().findById(entry.id())).isPresent();
        assertThat(repository().findById(entry.id()).orElseThrow().userId())
            .isEqualTo(entry.userId());
    }

    @Test
    @DisplayName("save после eliminate не теряет статус (перезагрузка видит ELIMINATED)")
    void save_сохраняет_статус_участия() {
        UUID tournamentId = newTournamentId();
        TournamentEntry entry = TournamentEntry.admit(tournamentId, UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), NOW);
        repository().save(entry);
        assertThat(repository().findById(entry.id()).orElseThrow().status())
            .isEqualTo(EntryStatus.ACTIVE);

        entry.eliminate();
        repository().save(entry);

        assertThat(repository().findById(entry.id()).orElseThrow().status())
            .isEqualTo(EntryStatus.ELIMINATED);
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

    @Test
    @DisplayName("глобальные участия: активное по пользователю, по статусу")
    void глобальные_участия() {
        TournamentEntryRepository repository = repository();
        UUID tournamentId = newTournamentId();
        UUID userId = UUID.randomUUID();
        TournamentEntry queued = TournamentEntry.queueForGlobal(tournamentId, userId,
            UUID.randomUUID(), UUID.randomUUID(), NOW);
        TournamentEntry withdrawn = TournamentEntry.queueForGlobal(tournamentId,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW);
        withdrawn.withdraw();
        repository.save(queued);
        repository.save(withdrawn);

        assertThat(repository.findActiveGlobalByUserId(tournamentId, userId))
            .map(TournamentEntry::id).contains(queued.id());
        assertThat(repository.findActiveGlobalByUserId(tournamentId, UUID.randomUUID()))
            .isEmpty();
        assertThat(repository.findByTournamentIdAndStatus(tournamentId, EntryStatus.QUEUED))
            .extracting(TournamentEntry::id).containsExactly(queued.id());
        assertThat(repository.findByTournamentIdAndStatus(tournamentId, EntryStatus.WITHDRAWN))
            .extracting(TournamentEntry::id).containsExactly(withdrawn.id());
    }
}
