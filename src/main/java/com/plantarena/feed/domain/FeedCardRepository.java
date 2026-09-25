package com.plantarena.feed.domain;

import java.util.List;
import java.util.UUID;

/**
 * Порт репозитория проекции карточек (ADR-002): запись — событиями
 * tournaments, чтение — keyset-страницами с псевдослучайным порядком.
 * Контракт фиксирует свойства порядка (детерминизм от seed, отсутствие
 * пропусков/дублей при продолжении курсора), а не значения хэша:
 * JPA-адаптер использует hashtextextended PostgreSQL, фейк — свой PRF.
 */
public interface FeedCardRepository {

    void saveAll(List<FeedCard> cards);

    void deleteAllByWindowId(UUID windowId);

    /** Страница длиной ≤ limit, отсортированная по (sortKey DESC, id DESC). */
    List<FeedCard> page(FeedCardQuery query);
}
