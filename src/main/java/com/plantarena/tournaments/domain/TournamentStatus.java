package com.plantarena.tournaments.domain;

/**
 * State machine закрытого турнира (раздел 7): DRAFT → REGISTRATION_OPEN →
 * RUNNING → FINISHED; DRAFT/REGISTRATION_OPEN → CANCELLED. FINISHED —
 * итерация 6 (выбывание до победителя).
 */
public enum TournamentStatus {
    DRAFT, REGISTRATION_OPEN, RUNNING, FINISHED, CANCELLED
}
