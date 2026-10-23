package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.SubmitGlobalEntryUseCase.GlobalEntryView;
import java.util.Optional;


public interface GetMyGlobalEntryUseCase {

    Optional<GlobalEntryView> findActive(CurrentActor actor);
}
