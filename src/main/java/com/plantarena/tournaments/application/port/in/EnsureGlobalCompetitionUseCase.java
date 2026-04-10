package com.plantarena.tournaments.application.port.in;

/** Идемпотентное создание единственной записи глобального турнира (ADR-012). */
public interface EnsureGlobalCompetitionUseCase {

    void ensure();
}
