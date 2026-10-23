package com.plantarena.feed.application;

import com.plantarena.feed.domain.FeedCursor;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Курсор ленты (раздел 9): HMAC-SHA256, tamper → 400, истёкший → 410. */
@DisplayName("FeedCursorCodec: подпись, tamper, TTL")
class FeedCursorCodecTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private final FeedCursorCodec codec =
        new FeedCursorCodec("test-secret", Duration.ofHours(1));

    @Test
    @DisplayName("roundtrip: encode → decode восстанавливает курсор")
    void roundtrip() {
        FeedCursor cursor = new FeedCursor(42L, NOW, -7L, UUID.randomUUID(),
            "USER:" + UUID.randomUUID());

        FeedCursor decoded = codec.decode(codec.encode(cursor), NOW.plusSeconds(10));

        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    @DisplayName("подмена payload или подписи — FeedCursorInvalidException")
    void подмена() {
        FeedCursor cursor = new FeedCursor(42L, NOW, null, null, "USER:x");
        String encoded = codec.encode(cursor);

        String[] parts = encoded.split("\\.");
        String tamperedPayload = parts[0] + "x"; // изменили payload
        assertThatThrownBy(() -> codec.decode(tamperedPayload + "." + parts[1], NOW))
            .isInstanceOf(FeedCursorInvalidException.class);
        assertThatThrownBy(() -> codec.decode(parts[0] + "." + parts[1].replaceFirst(".", "x"),
            NOW)).isInstanceOf(FeedCursorInvalidException.class);
        assertThatThrownBy(() -> codec.decode("garbage", NOW))
            .isInstanceOf(FeedCursorInvalidException.class);
    }

    @Test
    @DisplayName("чужой секрет — невалидная подпись")
    void чужой_секрет() {
        FeedCursor cursor = new FeedCursor(1L, NOW, null, null, "GUEST:" + UUID.randomUUID());
        String encoded = new FeedCursorCodec("other-secret", Duration.ofHours(1))
            .encode(cursor);

        assertThatThrownBy(() -> codec.decode(encoded, NOW))
            .isInstanceOf(FeedCursorInvalidException.class);
    }

    @Test
    @DisplayName("истёкший курсор (snapshotCutoff + ttl < now) — FeedCursorExpiredException")
    void истёкший() {
        FeedCursor cursor = new FeedCursor(1L, NOW, null, null, "USER:x");
        String encoded = codec.encode(cursor);

        assertThatThrownBy(() -> codec.decode(encoded, NOW.plus(Duration.ofHours(2))))
            .isInstanceOf(FeedCursorExpiredException.class);
        // ровно на границе TTL — ещё валиден
        assertThat(codec.decode(encoded, NOW.plus(Duration.ofHours(1)))).isEqualTo(cursor);
    }
}
