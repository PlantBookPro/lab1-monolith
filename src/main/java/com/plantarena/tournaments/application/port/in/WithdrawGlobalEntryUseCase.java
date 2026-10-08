package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;


public interface WithdrawGlobalEntryUseCase {

    void withdraw(CurrentActor actor, UUID entryId);
}
