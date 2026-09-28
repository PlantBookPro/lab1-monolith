package com.plantarena.tournaments;

import com.plantarena.tournaments.domain.QualificationEpoch;
import com.plantarena.tournaments.domain.QualificationEpochRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** Контрактный тест репозитория QualificationEpoch (раздел 14.2). */
@Transactional
@DisplayName("Контракт QualificationEpochRepository")
public abstract class QualificationEpochRepositoryContractTest {

    protected static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    protected abstract QualificationEpochRepository repository();

    /** Идентификатор турнира, на который можно ссылаться (FK в JPA-варианте). */
    protected abstract UUID newTournamentId();

    @Test
    @DisplayName("save → findOpenByTournamentId/findLastByTournamentId/findById")
    void сохранение_и_чтение() {
        QualificationEpochRepository repository = repository();
        UUID tournamentId = newTournamentId();
        QualificationEpoch first = epoch(tournamentId, 1);
        repository.save(first);
        QualificationEpoch second = epoch(tournamentId, 2);
        repository.save(second);

        assertThat(repository.findOpenByTournamentId(tournamentId))
            .map(QualificationEpoch::id).contains(first.id());
        assertThat(repository.findLastByTournamentId(tournamentId))
            .map(QualificationEpoch::sequence).contains(2);
        assertThat(repository.findById(first.id())).isPresent();

        first.close(first.closesAt());
        repository.save(first);
        assertThat(repository.findOpenByTournamentId(tournamentId))
            .map(QualificationEpoch::id).contains(second.id());
    }

    private QualificationEpoch epoch(UUID tournamentId, int sequence) {
        Instant opens = NOW;
        return QualificationEpoch.open(UUID.randomUUID(), tournamentId, sequence,
            opens, opens.plusSeconds(3600), opens);
    }
}
