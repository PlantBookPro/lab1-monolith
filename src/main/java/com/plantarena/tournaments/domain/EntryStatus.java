package com.plantarena.tournaments.domain;

/**
 * Участие в PRIVATE-турнире (раздел 11): ACTIVE → ELIMINATED/WINNER.
 * Переходы выбывания/победы — итерация 6 (закрытие окон).
 */
public enum EntryStatus {
    ACTIVE, ELIMINATED, WINNER
}
