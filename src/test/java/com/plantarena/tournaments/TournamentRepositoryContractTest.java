package com.plantarena.tournaments;

import com.plantarena.tournaments.domain.Tag;
import com.plantarena.tournaments.domain.TagRepository;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.TournamentStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт TournamentRepository (раздел 14.2): фейк и JPA + PostgreSQL честны
 * к одной семантике search/count/findDueForStart/delete.
 * @Transactional обязателен на базовом классе (урок итерации 2).
 */
@DisplayName("Контракт TournamentRepository")
@Transactional
public abstract class TournamentRepositoryContractTest {

    protected static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    protected abstract TournamentRepository repository();

    protected abstract TagRepository tags();

    private Tournament draft(UUID creatorId, Instant createdAt, UUID tagId) {
        return Tournament.restore(UUID.randomUUID(), creatorId, "Турнир " + createdAt,
            "Описание", com.plantarena.tournaments.domain.TournamentType.PRIVATE,
            TournamentStatus.DRAFT,
            com.plantarena.tournaments.domain.EliminationAlgorithmKind.ROUND_ELIMINATION,
            NOW.plusSeconds(86400), Duration.ofHours(1), 0.5, 2, null,
            tagId == null ? Set.of() : Set.of(tagId), createdAt, 0);
    }

    @Test
    @DisplayName("save/findById: полный roundtrip с тегами")
    void roundtrip() {
        UUID tagId = tags().save(Tag.create("Тег контракта", NOW)).id();
        Tournament tournament = draft(UUID.randomUUID(), NOW, tagId);
        repository().save(tournament);

        Tournament loaded = repository().findById(tournament.id()).orElseThrow();
        assertThat(loaded.name()).isEqualTo(tournament.name());
        assertThat(loaded.status()).isEqualTo(TournamentStatus.DRAFT);
        assertThat(loaded.registrationDeadline()).isEqualTo(tournament.registrationDeadline());
        assertThat(loaded.roundDuration()).isEqualTo(Duration.ofHours(1));
        assertThat(loaded.eliminationFraction()).isEqualTo(0.5);
        assertThat(loaded.minParticipants()).isEqualTo(2);
        assertThat(loaded.tagIds()).containsExactly(tagId);
        assertThat(loaded.version()).isEqualTo(tournament.version());
    }

    @Test
    @DisplayName("delete удаляет черновик")
    void delete_черновик() {
        Tournament tournament = draft(UUID.randomUUID(), NOW, null);
        repository().save(tournament);
        repository().delete(tournament.id());
        assertThat(repository().findById(tournament.id())).isEmpty();
    }

    @Test
    @DisplayName("search/count: админ видит все, фильтры статуса и тега, пагинация")
    void search_фильтры() {
        UUID tagId = tags().save(Tag.create("Фильтр", NOW)).id();
        Tournament first = draft(UUID.randomUUID(), NOW, tagId);
        Tournament second = draft(UUID.randomUUID(), NOW.plusSeconds(1), null);
        Tournament opened = draft(UUID.randomUUID(), NOW.plusSeconds(2), null);
        opened.openRegistration(NOW);
        repository().save(first);
        repository().save(second);
        repository().save(opened);

        var adminAll = new TournamentRepository.TournamentFilter(null, true, null, null, 0, 50);
        assertThat(repository().search(adminAll)).hasSize(3);
        assertThat(repository().count(adminAll)).isEqualTo(3);

        var byStatus = new TournamentRepository.TournamentFilter(null, true,
            TournamentStatus.REGISTRATION_OPEN, null, 0, 50);
        assertThat(repository().search(byStatus)).extracting(Tournament::id)
            .containsExactly(opened.id());

        var byTag = new TournamentRepository.TournamentFilter(null, true, null, tagId, 0, 50);
        assertThat(repository().search(byTag)).extracting(Tournament::id)
            .containsExactly(first.id());

        var page = new TournamentRepository.TournamentFilter(null, true, null, null, 2, 2);
        assertThat(repository().search(page)).hasSize(1); // createdAt desc + id
    }

    @Test
    @DisplayName("findDueForStart: только REGISTRATION_OPEN с наступившим дедлайном")
    void find_due_for_start() {
        Tournament due = draft(UUID.randomUUID(), NOW, null);
        due.openRegistration(NOW);
        due.start(NOW.plusSeconds(86400), 2); // RUNNING — не due
        Tournament notDue = draft(UUID.randomUUID(), NOW, null);
        notDue.openRegistration(NOW);
        Tournament stillDraft = draft(UUID.randomUUID(), NOW, null);
        repository().save(due);
        repository().save(notDue);
        repository().save(stillDraft);

        assertThat(repository().findDueForStart(NOW.plusSeconds(86400), 10))
            .extracting(Tournament::id)
            .containsExactly(notDue.id());
    }
}
