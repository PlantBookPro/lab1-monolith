package com.plantarena.tournaments.domain;


public final class EliminationAlgorithms {

    private EliminationAlgorithms() {
    }

    public static EliminationAlgorithm forKind(EliminationAlgorithmKind kind) {
        return switch (kind) {
            case ROUND_ELIMINATION -> new RoundElimination();
        };
    }
}
