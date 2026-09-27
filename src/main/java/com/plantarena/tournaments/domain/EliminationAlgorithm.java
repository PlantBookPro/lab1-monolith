package com.plantarena.tournaments.domain;

/** Стратегия выбывания (раздел 5): сколько худших выбывает из окна. */
public interface EliminationAlgorithm {

    /**
     * Число выбывающих из n участников.
     *
     * @throws IllegalArgumentException n &lt; 2 или доля вне (0, 1)
     */
    int eliminatedCount(int participants, double eliminationFraction);
}
