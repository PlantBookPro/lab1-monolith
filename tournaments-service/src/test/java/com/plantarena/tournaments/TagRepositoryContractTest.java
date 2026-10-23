package com.plantarena.tournaments;

import com.plantarena.tournaments.domain.Tag;
import com.plantarena.tournaments.domain.TagRepository;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** Контракт TagRepository: уникальность имени, isUsedByTournament, пагинация. */
@DisplayName("Контракт TagRepository")
@Transactional
public abstract class TagRepositoryContractTest {

    protected static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    protected abstract TagRepository repository();

    protected abstract TournamentRepository tournaments();

    @Test
    @DisplayName("save/findByName/findAll/delete и использование турниром")
    void crud_и_использование() {
        Tag tag = repository().save(Tag.create("Контрактный", NOW));
        repository().save(Tag.create("Второй", NOW));

        assertThat(repository().findById(tag.id())).contains(tag);
        assertThat(repository().findByName("Контрактный")).contains(tag);
        assertThat(repository().findByName("Нет такого")).isEmpty();
        assertThat(repository().findAll(0, 1)).hasSize(1);
        assertThat(repository().count()).isEqualTo(2);
        assertThat(repository().isUsedByTournament(tag.id())).isFalse();

        Tournament tournament = Tournament.createDraft(UUID.randomUUID(), "Т", null,
            NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2, Set.of(tag.id()), NOW);
        tournaments().save(tournament);
        assertThat(repository().isUsedByTournament(tag.id())).isTrue();

        repository().delete(tag.id());
        assertThat(repository().findById(tag.id())).isEmpty();
    }
}
