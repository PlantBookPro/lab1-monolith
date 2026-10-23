package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.domain.EpochStatus;
import com.plantarena.tournaments.domain.QualificationEpoch;
import com.plantarena.tournaments.domain.QualificationEpochRepository;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory фейк QualificationEpochRepository. */
public class InMemoryQualificationEpochRepository implements QualificationEpochRepository {

    public final Map<UUID, QualificationEpoch> epochs = new ConcurrentHashMap<>();

    @Override
    public QualificationEpoch save(QualificationEpoch epoch) {
        epochs.put(epoch.id(), epoch);
        return epoch;
    }

    @Override
    public Optional<QualificationEpoch> findById(UUID id) {
        return Optional.ofNullable(epochs.get(id));
    }

    @Override
    public Optional<QualificationEpoch> findOpenByTournamentId(UUID tournamentId) {
        return epochs.values().stream()
            .filter(epoch -> epoch.tournamentId().equals(tournamentId)
                && epoch.status() == EpochStatus.OPEN)
            .max(Comparator.comparingInt(QualificationEpoch::sequence));
    }

    @Override
    public Optional<QualificationEpoch> findLastByTournamentId(UUID tournamentId) {
        return epochs.values().stream()
            .filter(epoch -> epoch.tournamentId().equals(tournamentId))
            .max(Comparator.comparingInt(QualificationEpoch::sequence));
    }
}
