package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.TournamentRepository.TournamentFilter;
import com.plantarena.tournaments.domain.TournamentStatus;
import com.plantarena.tournaments.domain.TournamentType;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory фейк TournamentRepository: search честно повторяет семантику
 * «доступные» (организатор/активное приглашение/участие; админ — все),
 * как и JPA-реализация (контрактные тесты — Task 6).
 */
public class InMemoryTournamentRepository implements TournamentRepository {

    public final Map<UUID, Tournament> tournaments = new ConcurrentHashMap<>();
    public final Map<UUID, List<UUID>> invitationsByTournament = new ConcurrentHashMap<>();
    public final Map<UUID, List<UUID>> activeInvitationUsers = new ConcurrentHashMap<>();
    public final Map<UUID, List<UUID>> entryUsers = new ConcurrentHashMap<>();

    /** Регистрация связей для search (вызывается InMemoryInvitationRepository/Entry). */
    public void trackInvitation(UUID tournamentId, UUID userId, InvitationStatus status) {
        List<UUID> users = activeInvitationUsers.computeIfAbsent(tournamentId,
            key -> new java.util.concurrent.CopyOnWriteArrayList<>());
        if (status == InvitationStatus.INVITED || status == InvitationStatus.READY
                || status == InvitationStatus.ACCEPTED_PENDING_MODERATION) {
            if (!users.contains(userId)) {
                users.add(userId);
            }
        } else {
            users.remove(userId);
        }
    }

    public void trackEntry(UUID tournamentId, UUID userId) {
        entryUsers.computeIfAbsent(tournamentId,
                key -> new java.util.concurrent.CopyOnWriteArrayList<>())
            .add(userId);
    }

    @Override
    public Tournament save(Tournament tournament) {
        tournaments.put(tournament.id(), tournament);
        return tournament;
    }

    @Override
    public Optional<Tournament> findById(UUID id) {
        return Optional.ofNullable(tournaments.get(id));
    }

    @Override
    public void delete(UUID id) {
        tournaments.remove(id);
    }

    @Override
    public List<Tournament> search(TournamentFilter filter) {
        return filtered(filter)
            .sorted(Comparator.comparing(Tournament::createdAt).reversed()
                .thenComparing(Tournament::id))
            .skip(filter.offset())
            .limit(filter.size())
            .toList();
    }

    @Override
    public long count(TournamentFilter filter) {
        return filtered(filter).count();
    }

    @Override
    public List<Tournament> findDueForStart(Instant now, int limit) {
        return tournaments.values().stream()
            .filter(tournament -> tournament.status() == TournamentStatus.REGISTRATION_OPEN
                && !tournament.registrationDeadline().isAfter(now))
            .sorted(Comparator.comparing(Tournament::registrationDeadline)
                .thenComparing(Tournament::id))
            .limit(limit)
            .toList();
    }

    private java.util.stream.Stream<Tournament> filtered(TournamentFilter filter) {
        return tournaments.values().stream()
            .filter(tournament -> tournament.type() == TournamentType.PRIVATE)
            .filter(tournament -> filter.admin()
                || filter.userId() == null // системный вызов без пользователя
                || tournament.creatorId().equals(filter.userId())
                || activeInvitationUsers.getOrDefault(tournament.id(), List.of())
                    .contains(filter.userId())
                || entryUsers.getOrDefault(tournament.id(), List.of())
                    .contains(filter.userId()))
            .filter(tournament -> filter.status() == null
                || tournament.status() == filter.status())
            .filter(tournament -> filter.tagId() == null
                || tournament.tagIds().contains(filter.tagId()));
    }
}
