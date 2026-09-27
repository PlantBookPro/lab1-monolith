package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.EntryStatus;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Реализация порта TournamentEntryRepository (создание при старте). */
@Repository
@Transactional
public class JpaTournamentEntryRepository implements TournamentEntryRepository {

    private final TournamentEntryJpaRepository entries;
    private final TournamentJpaRepository tournaments;

    public JpaTournamentEntryRepository(TournamentEntryJpaRepository entries,
                                        TournamentJpaRepository tournaments) {
        this.entries = entries;
        this.tournaments = tournaments;
    }

    @Override
    public TournamentEntry save(TournamentEntry entry) {
        TournamentJpaEntity tournament = tournaments.findById(entry.tournamentId())
            .orElseThrow(() -> new DataIntegrityViolationException(
                "Турнир участия не найден: " + entry.tournamentId()));
        TournamentEntryJpaEntity entity = entries.findById(entry.id())
            .orElseGet(() -> new TournamentEntryJpaEntity(entry.id(), tournament,
                entry.userId(), entry.plantId(), entry.reservationId(), entry.joinedAt()));
        entity.setStatus(entry.status().name());
        return toDomain(entries.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TournamentEntry> findByTournamentId(UUID tournamentId, int offset, int size) {
        return entries.findByTournamentIdOrderByJoinedAtAscIdAsc(tournamentId,
                PageRequest.of(offset / size, size))
            .stream().map(JpaTournamentEntryRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByTournamentId(UUID tournamentId) {
        return entries.countByTournamentId(tournamentId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByTournamentIdAndUserId(UUID tournamentId, UUID userId) {
        return entries.existsByTournamentIdAndUserId(tournamentId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TournamentEntry> findById(UUID id) {
        return entries.findById(id).map(JpaTournamentEntryRepository::toDomain);
    }

    private static TournamentEntry toDomain(TournamentEntryJpaEntity entity) {
        return TournamentEntry.restore(entity.getId(), entity.getTournament().getId(),
            entity.getUserId(), entity.getPlantId(), entity.getReservationId(),
            EntryStatus.valueOf(entity.getStatus()), entity.getJoinedAt());
    }
}
