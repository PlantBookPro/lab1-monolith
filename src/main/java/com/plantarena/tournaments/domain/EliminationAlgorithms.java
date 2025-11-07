package com.plantarena.tournaments.domain;

/** Фабрика стратегий выбывания по алгоритму турнира (раздел 7). */
public final class EliminationAlgorithms {

    private EliminationAlgorithms() {
    }

    public static EliminationAlgorithm forKind(EliminationAlgorithmKind kind) {
        return switch (kind) {
            case ROUND_ELIMINATION -> new RoundElimination();
        };
    }
}
