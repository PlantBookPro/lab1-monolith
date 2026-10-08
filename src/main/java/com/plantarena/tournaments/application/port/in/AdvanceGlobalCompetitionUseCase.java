package com.plantarena.tournaments.application.port.in;

import java.time.Instant;


public interface AdvanceGlobalCompetitionUseCase {

    AdvanceReport advance(Instant now);

    
    record AdvanceReport(int qualificationClosed, int finalClosed, int finalsOpened,
                         int epochsOpened) {
    }
}
