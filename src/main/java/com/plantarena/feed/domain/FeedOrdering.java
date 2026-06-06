package com.plantarena.feed.domain;

import java.security.SecureRandom;

/**
 * Псевдослучайный порядок ленты (раздел 9): server-issued seed + стабильный
 * ID карточки. Sort key вычисляется на стороне хранилища (hashtextextended
 * в PostgreSQL) — таблица не грузится в память; стоимость сортировки
 * O(k log k) по отфильтрованным строкам на страницу, индекса по выражению
 * с параметром нет (документировано, ADR-002).
 */
public class FeedOrdering {

    private final SecureRandom random = new SecureRandom();

    /** Новый seed ленты (выдаётся при первом запросе, переносится курсором). */
    public long newSeed() {
        return random.nextLong();
    }
}
