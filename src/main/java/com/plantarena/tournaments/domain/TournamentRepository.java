package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Порт репозитория агрегата Tournament. search/count — список доступных
 * пользователю турниров (организатор/активное приглашение/участие; админ —
 * все) с фильтрами статуса и тега (раздел 13); findDueForStart —
 * REGISTRATION_OPEN с наступившим дедлайном (scheduler, раздел 7).
 */
public interface TournamentRepository {

    Tournament save(Tournament tournament);

    Optional<Tournament> findById(UUID id);

    /** Удаление пустого черновика (проверка «пустой» — в application). */
    void delete(UUID id);

    List<Tournament> search(TournamentFilter filter);

    long count(TournamentFilter filter);

    /** Турниры, готовые к старту/отмене по дедлайну: deadline <= now. */
    List<Tournament> findDueForStart(Instant now, int limit);

    /**
     * @param userId пользователь (null для админа — видеть все)
     * @param admin  признак роли ADMIN
     * @param status фильтр по статусу (null — любой)
     * @param tagId  фильтр по тегу (null — любой)
     */
    record TournamentFilter(UUID userId, boolean admin, TournamentStatus status, UUID tagId,
                            int offset, int size) {
    }
}
