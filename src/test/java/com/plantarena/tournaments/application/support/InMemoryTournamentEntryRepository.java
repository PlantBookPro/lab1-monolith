package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataIntegrityViolationException;

/** In-memory фейк TournamentEntryRepository: честен к UNIQUE(tournamentId, userId). */
public class InMemoryTournamentEntryRepository implements TournamentEntryRepository {

    public final Map<UUID, TournamentEntry> entries = new ConcurrentHashMap<>();
    private final InMemoryTournamentRepository tournaments;

    public InMemoryTournamentEntryRepository() {
        this(null);
    }

    public InMemoryTournamentEntryRepository(InMemoryTournamentRepository tournaments) {
        this.tournaments = tournaments;
    }

    @Override
    public TournamentEntry save(TournamentEntry entry) {
        entries.values().stream()
            .filter(existing -> existing.tournamentId().equals(entry.tournamentId())
                && existing.userId().equals(entry.userId())
                && !existing.id().equals(entry.id()))
            .findAny()
            .ifPresent(existing -> {
                throw new DataIntegrityViolationException(
                    "Участие пары уже существует: " + existing.id());
            });
        entries.put(entry.id(), entry);
        if (tournaments != null) {
            tournaments.trackEntry(entry.tournamentId(), entry.userId());
        }
        return entry;
    }

    @Override
    public List<TournamentEntry> findByTournamentId(UUID tournamentId, int offset, int size) {
        return entries.values().stream()
            .filter(entry -> entry.tournamentId().equals(tournamentId))
            .sorted(Comparator.comparing(TournamentEntry::id))
            .skip(offset).limit(size).toList();
    }

    @Override
    public long countByTournamentId(UUID tournamentId) {
        return entries.values().stream()
            .filter(entry -> entry.tournamentId().equals(tournamentId))
            .count();
    }

    @Override
    public boolean existsByTournamentIdAndUserId(UUID tournamentId, UUID userId) {
        return entries.values().stream()
            .anyMatch(entry -> entry.tournamentId().equals(tournamentId)
                && entry.userId().equals(userId));
    }

    @Override
    public Optional<TournamentEntry> findById(UUID id) {
        return Optional.ofNullable(entries.get(id));
    }
}
