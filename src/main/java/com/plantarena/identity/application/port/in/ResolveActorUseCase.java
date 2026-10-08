package com.plantarena.identity.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;


public interface ResolveActorUseCase {

    CurrentActor resolve(UUID userId);
}
