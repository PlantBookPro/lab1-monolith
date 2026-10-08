package com.plantarena.tournaments.domain;


public final class RoundElimination implements EliminationAlgorithm {

    @Override
    public int eliminatedCount(int participants, double eliminationFraction) {
        if (participants < 2) {
            throw new IllegalArgumentException("Выбывание считается при n >= 2: " + participants);
        }
        if (eliminationFraction <= 0d || eliminationFraction >= 1d) {
            throw new IllegalArgumentException("Доля выбывания в (0, 1): " + eliminationFraction);
        }
        return Math.min(participants - 1,
            Math.max(1, (int) Math.floor(participants * eliminationFraction)));
    }
}
