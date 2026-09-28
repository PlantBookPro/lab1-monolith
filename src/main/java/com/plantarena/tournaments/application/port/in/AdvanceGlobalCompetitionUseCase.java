package com.plantarena.tournaments.application.port.in;

import java.time.Instant;

/**
 * Продвижение границ глобального турнира (раздел 8, алгоритм 6): один use
 * case для scheduler'а и demo-ручки. Фиксированный порядок: закрыть
 * квалификацию → закрыть финал → открыть следующий финал → открыть
 * следующую эпоху. Идемпотентен по статусам (рестарт без повторной гибели).
 */
public interface AdvanceGlobalCompetitionUseCase {

    AdvanceReport advance(Instant now);

    /** Счётчики выполненных шагов (диагностика demo-ручки). */
    record AdvanceReport(int qualificationClosed, int finalClosed, int finalsOpened,
                         int epochsOpened) {
    }
}
