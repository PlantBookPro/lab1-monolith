package com.plantarena.tournaments.api.client;

import static org.assertj.core.api.Assertions.assertThat;

import feign.FeignException;
import feign.Request;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ApiErrorDecoder: тело FeignException (RFC 9457 ApiError) → ApiError")
class ApiErrorDecoderTest {

    private FeignException.NotFound notFound(String body) {
        Request request = Request.create(Request.HttpMethod.GET, "/internal/plants/00000000-0000-0000-0000-000000000000",
            Map.of(), null, StandardCharsets.UTF_8, null);
        return new FeignException.NotFound("404", request, body.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    @Test
    void decodesPlantNotEligibleWithRetryAt() {
        FeignException e = notFound("""
            {"type":"about:blank","title":"Conflict","status":409,
             "detail":"Растение не проходит условия участия","instance":"/internal/plants/reservations",
             "code":"PLANT_NOT_ELIGIBLE","traceId":"t-1","retryAt":"2026-10-24T00:00:00Z"}
            """);

        var error = ApiErrorDecoder.decode(e);

        assertThat(error).isPresent();
        assertThat(error.get().code()).isEqualTo("PLANT_NOT_ELIGIBLE");
        assertThat(error.get().detail()).isEqualTo("Растение не проходит условия участия");
        assertThat(error.get().retryAt()).isEqualTo(Instant.parse("2026-10-24T00:00:00Z"));
    }

    @Test
    void decodesReservationConflict() {
        FeignException e = notFound("""
            {"status":409,"detail":"изображение уже занято","code":"RESERVATION_CONFLICT"}
            """);

        var error = ApiErrorDecoder.decode(e);

        assertThat(error).isPresent();
        assertThat(error.get().code()).isEqualTo("RESERVATION_CONFLICT");
        assertThat(error.get().retryAt()).isNull();
    }

    @Test
    void emptyOnGarbageBody() {
        assertThat(ApiErrorDecoder.decode(notFound("<html>502 Bad Gateway</html>"))).isEmpty();
        assertThat(ApiErrorDecoder.decode(notFound(""))).isEmpty();
    }
}
