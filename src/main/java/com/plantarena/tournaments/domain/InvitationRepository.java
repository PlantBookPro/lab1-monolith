package com.plantarena.tournaments.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Порт репозитория агрегата Invitation. Уникальность (tournamentId, userId)
 * — БД (UNIQUE); findBySubmittedPlantIdAndStatus — реакция на решение
 * модерации (раздел 4.3).
 */
public interface InvitationRepository {

    Invitation save(Invitation invitation);

    Optional<Invitation> findById(UUID id);

    Optional<Invitation> findByTournamentIdAndUserId(UUID tournamentId, UUID userId);

    /** Заявка с растением в данном статусе (реакция на PlantModerationDecided). */
    Optional<Invitation> findBySubmittedPlantIdAndStatus(UUID plantId, InvitationStatus status);

    List<Invitation> findByTournamentId(UUID tournamentId, int offset, int size);

    long countByTournamentId(UUID tournamentId);

    List<Invitation> findByUserId(UUID userId, int offset, int size);

    long countByUserId(UUID userId);

    List<Invitation> findByTournamentIdAndStatus(UUID tournamentId, InvitationStatus status);

    /** Есть ли хоть одно приглашение (проверка «пустой черновик» при удалении). */
    boolean existsByTournamentId(UUID tournamentId);
}
