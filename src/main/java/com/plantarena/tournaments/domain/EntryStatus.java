package com.plantarena.tournaments.domain;

/**
 * Статус участия (раздел 11). PRIVATE: ACTIVE → ELIMINATED/WINNER.
 * GLOBAL: QUEUED → QUALIFYING → FINAL_PENDING → FINALIST → ELIMINATED,
 * плюс WITHDRAWN только из QUEUED; QUALIFYING → ELIMINATED допустим.
 */
public enum EntryStatus {
    ACTIVE, ELIMINATED, WINNER,
    QUEUED, QUALIFYING, FINAL_PENDING, FINALIST, WITHDRAWN
}
