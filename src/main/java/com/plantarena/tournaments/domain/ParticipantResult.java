package com.plantarena.tournaments.domain;

/**
 * Итог участника окна (раздел 11): ACTIVE — идёт голосование; SURVIVED —
 * пережил окно (следующий раунд); ELIMINATED — выбыл; WINNER — победил.
 * PROMOTED (глобальная квалификация) — итерация 7.
 */
public enum ParticipantResult {
    ACTIVE, SURVIVED, ELIMINATED, WINNER
}
