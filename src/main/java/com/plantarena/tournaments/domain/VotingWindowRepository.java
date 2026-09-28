package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Порт репозитория агрегата VotingWindow (разделы 9, 12.1). */
public interface VotingWindowRepository {

    VotingWindow save(VotingWindow window);

    Optional<VotingWindow> findById(UUID id);

    /**
     * Загрузка с блокировкой окна (раздел 12.1): SELECT ... FOR UPDATE в
     * JPA-адаптере; сериализует голоса и закрытие одного окна. Проверку
     * времени вызывающий выполняет после получения блокировки.
     */
    Optional<VotingWindow> findByIdForUpdate(UUID id);

    /** Просроченные OPEN-окна (closesAt <= now) для scheduler'а закрытия. */
    List<UUID> findDueForClose(Instant now, int limit);

    List<VotingWindow> findByTournamentId(UUID tournamentId, int offset, int size);

    long countByTournamentId(UUID tournamentId);

    /** Последнее окно турнира (максимум sequence) — лидерборд по умолчанию. */
    Optional<VotingWindow> findLatestByTournamentId(UUID tournamentId);

    /** Все окна турнира (итоги: раунд выбывания каждого entry; турнир конечен). */
    List<VotingWindow> findAllByTournamentId(UUID tournamentId);

    /** Просроченные OPEN-окна конкретного scope (глобальные границы, раздел 8). */
    List<UUID> findDueForCloseByScope(WindowScope scope, Instant now, int limit);

    /** Открытое окно турнира в scope (финал: не более одного — индекс). */
    Optional<VotingWindow> findOpenByScope(UUID tournamentId, WindowScope scope);

    /** Последнее окно турнира в scope (нумерация финальных окон). */
    Optional<VotingWindow> findLatestByTournamentIdAndScope(UUID tournamentId, WindowScope scope);

    /** Открытые окна эпохи (кластеры текущего отбора). */
    List<VotingWindow> findOpenByEpochId(UUID epochId);

    /** Открытое окно кластера (лидерборд кластера, алгоритм 9). */
    Optional<VotingWindow> findOpenByClusterId(UUID clusterId);

    /** Сколько открытых окон осталось у эпохи (закрытие эпохи). */
    long countOpenByEpochId(UUID epochId);
}
