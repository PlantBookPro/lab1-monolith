package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.Optional;
import java.util.UUID;


public interface VotingUseCase {

    
    long cast(CurrentActor actor, String guestToken, UUID windowId, UUID entryId, String value);

    
    long remove(CurrentActor actor, String guestToken, UUID windowId, UUID entryId);

    
    Optional<String> myVote(CurrentActor actor, String guestToken, UUID windowId, UUID entryId);
}
