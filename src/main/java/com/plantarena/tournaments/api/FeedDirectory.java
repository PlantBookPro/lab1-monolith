package com.plantarena.tournaments.api;

import java.util.Set;
import java.util.UUID;

/**
 * Read-контракт tournaments для feed (раздел 9): только чтение, без
 * доменных типов. Реализация — фасад application (FeedDirectoryFacade).
 */
public interface FeedDirectory {

    /** Entry, уже оценённые субъектом в открытых окнах (карточки не предлагаются). */
    Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey);

    /** Турниры, где пользователь был допущен к старту (включая выбывших — допущение 9). */
    Set<UUID> findParticipatedTournamentIds(UUID userId);
}
