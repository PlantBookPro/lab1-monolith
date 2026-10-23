package com.plantarena.tournaments.domain;


public interface EliminationAlgorithm {

    
    int eliminatedCount(int participants, double eliminationFraction);
}
