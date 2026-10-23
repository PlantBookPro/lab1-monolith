package com.plantarena.shared.webflux;

import com.plantarena.shared.security.CurrentActor;
import reactor.core.publisher.Mono;

public interface ReactiveCurrentActorProvider {

    Mono<CurrentActor> currentActor();
}
