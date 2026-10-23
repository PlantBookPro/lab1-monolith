package com.plantarena.shared.webflux;

import java.util.concurrent.Callable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class Blocking {

    private Blocking() {}

    public static <T> Mono<T> call(Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }
}
