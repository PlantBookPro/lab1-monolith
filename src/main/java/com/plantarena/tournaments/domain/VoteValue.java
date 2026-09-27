package com.plantarena.tournaments.domain;

/**
 * Значение голоса (раздел 9): LIKE/DISLIKE, числовой вклад +1/−1. Дельты
 * переходов — чистая доменная функция: previous == null — голоса не было,
 * next == null — удаление.
 */
public enum VoteValue {
    LIKE, DISLIKE;

    /** Числовый вклад текущего голоса в счёт. */
    public long contribution() {
        return this == LIKE ? 1L : -1L;
    }

    /**
     * Дельта счёта при переходе previous → next (раздел 9): новый голос —
     * вклад, смена знака — ±2, повтор того же значения — 0, удаление —
     * компенсация вклада.
     */
    public static long transitionDelta(VoteValue previous, VoteValue next) {
        if (previous == next) {
            return 0L;
        }
        if (previous == null) {
            return next.contribution();
        }
        if (next == null) {
            return -previous.contribution();
        }
        return next.contribution() - previous.contribution();
    }
}
