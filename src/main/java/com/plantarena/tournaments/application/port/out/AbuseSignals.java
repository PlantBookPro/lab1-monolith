package com.plantarena.tournaments.application.port.out;

/**
 * Журнал подозрительных действий (раздел 9): выходной порт; в лабе №1 —
 * логирующий адаптер, в целевой системе — anti-doping-service.
 */
public interface AbuseSignals {

    void signal(String action, String details);
}
