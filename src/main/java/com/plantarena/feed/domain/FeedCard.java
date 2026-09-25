package com.plantarena.feed.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Карточка ленты (раздел 9, ADR-002): строка read-модели — снимок участника
 * открытого окна на момент его открытия (title и displayName зафиксированы,
 * смена названий не ретранслируется). sortKey заполнен только в результатах
 * page() — псевдослучайный ключ от seed и стабильного id.
 */
public record FeedCard(
        UUID id,
        UUID windowId,
        UUID tournamentId,
        String scope,
        UUID clusterId,
        UUID entryId,
        UUID userId,
        UUID plantId,
        UUID assetId,
        String title,
        String ownerDisplayName,
        Instant joinedAt,
        Instant closesAt,
        Instant createdAt,
        long sortKey) {
}
