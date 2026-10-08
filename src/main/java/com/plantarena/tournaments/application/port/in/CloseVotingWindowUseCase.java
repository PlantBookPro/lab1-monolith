package com.plantarena.tournaments.application.port.in;

import java.time.Instant;


public interface CloseVotingWindowUseCase {

    
    int closeDue(Instant now, int limit);
}
