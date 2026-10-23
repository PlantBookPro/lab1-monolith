package com.plantarena.feed.api.client.fallback;

import com.plantarena.feed.api.client.ApiErrorDecoder;
import feign.FeignException;
import org.springframework.cloud.openfeign.FallbackFactory;

abstract class AbstractClientFallbackFactory<T> implements FallbackFactory<T> {

    private final T fallback;

    AbstractClientFallbackFactory(T fallback) {
        this.fallback = fallback;
    }

    @Override
    public T create(Throwable cause) {
        if (ApiErrorDecoder.isBusinessError(cause)) {
            throw (FeignException) cause;
        }
        return fallback;
    }
}
