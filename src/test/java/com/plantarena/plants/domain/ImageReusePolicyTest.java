package com.plantarena.plants.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Политика повторного использования: постоянный запрет приоритетнее временного")
class ImageReusePolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final ImageFingerprint FINGERPRINT =
        new ImageFingerprint("e".repeat(64), 1);
    private final ImageReusePolicy policy = new ImageReusePolicy();

    private ImageRestriction permanent() {
        return ImageRestriction.permanent(UUID.randomUUID(), FINGERPRINT,
            "закрытый турнир", null, NOW.minusSeconds(1));
    }

    private ImageRestriction cooldown(Instant expiresAt) {
        return ImageRestriction.cooldown(UUID.randomUUID(), FINGERPRINT,
            "глобальный турнир", null, expiresAt, NOW.minusSeconds(1));
    }

    @Test
    void без_запретов_повторное_использование_разрешено() {
        assertThat(policy.activeRestriction(List.of(), NOW)).isEmpty();
    }

    @Test
    void постоянный_запрет_действует_всегда() {
        assertThat(policy.activeRestriction(List.of(permanent()), NOW.plusSeconds(10_000_000)))
            .isPresent()
            .get()
            .extracting(ImageRestriction::kind)
            .isEqualTo(RestrictionKind.PERMANENT);
    }

    @Test
    void суточный_запрет_действует_до_истечения() {
        assertThat(policy.activeRestriction(List.of(cooldown(NOW.plusSeconds(3600))), NOW))
            .isPresent();
        assertThat(policy.activeRestriction(List.of(cooldown(NOW.plusSeconds(3600))),
            NOW.plusSeconds(3600)))
            .isEmpty(); // now >= expiresAt — разрешено (полуоткрытый интервал)
    }

    @Test
    void постоянный_запрет_приоритетнее_временного() {
        ImageRestriction result = policy.activeRestriction(
            List.of(cooldown(NOW.plusSeconds(3600)), permanent()), NOW).orElseThrow();

        assertThat(result.kind()).isEqualTo(RestrictionKind.PERMANENT);
    }

    @Test
    void из_нескольких_временных_действует_самый_поздний() {
        ImageRestriction result = policy.activeRestriction(
            List.of(cooldown(NOW.plusSeconds(600)), cooldown(NOW.plusSeconds(3600))), NOW)
            .orElseThrow();

        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
    }
}
