package com.plantarena.tournaments.application;

import java.util.UUID;

/** Фиксированный id единственного глобального турнира (раздел 8, ADR-012). */
public final class GlobalCompetitionId {

    public static final UUID VALUE = UUID.fromString("00000007-10ba-4000-8000-000000000001");

    private GlobalCompetitionId() {
    }
}
