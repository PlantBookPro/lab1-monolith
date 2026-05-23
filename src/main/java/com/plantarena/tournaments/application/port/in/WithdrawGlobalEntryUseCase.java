package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/** Снятие заявки из очереди глобального турнира (раздел 13). */
public interface WithdrawGlobalEntryUseCase {

    void withdraw(CurrentActor actor, UUID entryId);
}
