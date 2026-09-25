package com.plantarena.feed.application.port.out;

import java.util.Set;
import java.util.UUID;

/**
 * Выходной порт feed: данные голосования из tournaments (раздел 9).
 * Адаптер — ACL над tournaments.api.FeedDirectory; feed не читает чужие
 * таблицы (ADR-002).
 */
public interface VotingDirectory {

    /** Entry, уже оценённые субъектом в открытых окнах (карточки не предлагаются). */
    Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey);

    /** Турниры, где пользователь был допущен к старту (включая выбывших — допущение 9). */
    Set<UUID> findParticipatedTournamentIds(UUID userId);
}
