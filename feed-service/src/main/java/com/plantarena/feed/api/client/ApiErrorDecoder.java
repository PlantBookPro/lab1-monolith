package com.plantarena.feed.api.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantarena.shared.web.ApiError;
import feign.FeignException;
import java.util.Optional;

public final class ApiErrorDecoder {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private ApiErrorDecoder() {}

    public static Optional<ApiError> decode(FeignException e) {
        try {
            return Optional.ofNullable(MAPPER.readValue(e.contentUTF8(), ApiError.class));
        } catch (Exception parseFailure) {
            return Optional.empty();
        }
    }
}
