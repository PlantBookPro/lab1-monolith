package com.plantarena.plants.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Агрегат ImageRestriction: PERMANENT без expiresAt, COOLDOWN с expiresAt")
class ImageRestrictionTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final ImageFingerprint FINGERPRINT =
        new ImageFingerprint("c".repeat(64), 1);

    @Test
    void постоянный_запрет_без_истечения() {
        ImageRestriction restriction =
            ImageRestriction.permanent(UUID.randomUUID(), FINGERPRINT,
                "поражение в закрытом турнире", UUID.randomUUID(), NOW);

        assertThat(restriction.kind()).isEqualTo(RestrictionKind.PERMANENT);
        assertThat(restriction.expiresAt()).isNull();
    }

    @Test
    void суточный_запрет_с_истечением() {
        ImageRestriction restriction =
            ImageRestriction.cooldown(UUID.randomUUID(), FINGERPRINT,
                "поражение в глобальном турнире", UUID.randomUUID(),
                NOW.plusSeconds(86400), NOW);

        assertThat(restriction.kind()).isEqualTo(RestrictionKind.COOLDOWN);
        assertThat(restriction.expiresAt()).isEqualTo(NOW.plusSeconds(86400));
    }

    @Test
    void причина_обязательна_для_истории() {
        assertThatThrownBy(() -> ImageRestriction.permanent(UUID.randomUUID(), FINGERPRINT,
            "  ", null, NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
