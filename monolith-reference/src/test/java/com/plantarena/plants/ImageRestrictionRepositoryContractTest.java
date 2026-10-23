package com.plantarena.plants;

import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.plants.domain.ImageRestrictionRepository;
import com.plantarena.plants.domain.RestrictionKind;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт ImageRestrictionRepository (раздел 14.2): честность фейка.
 * @Transactional обязателен на базовом классе (урок итерации 2, фикс 56e8e3a).
 */
@DisplayName("Контракт ImageRestrictionRepository")
@Transactional
public abstract class ImageRestrictionRepositoryContractTest {

    protected static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    protected static final ImageFingerprint FINGERPRINT = new ImageFingerprint("b".repeat(64), 1);

    protected abstract ImageRestrictionRepository repository();

    @Test
    @DisplayName("история запретов пары сохраняется полностью (append-only)")
    void история_запретов_пары_сохраняется_полностью() {
        UUID ownerId = UUID.randomUUID();
        UUID sourceEntryId = UUID.randomUUID();
        repository().save(ImageRestriction.permanent(ownerId, FINGERPRINT,
            "поражение в закрытом турнире", sourceEntryId, NOW));
        repository().save(ImageRestriction.cooldown(ownerId, FINGERPRINT,
            "поражение в глобальном турнире", null, NOW.plus(Duration.ofHours(24)),
            NOW.plusSeconds(30)));

        var history = repository().findByOwnerAndFingerprint(ownerId, FINGERPRINT.value());

        assertThat(history).hasSize(2); // история не удаляется и не перезаписывается
        assertThat(history).extracting(ImageRestriction::kind)
            .containsExactlyInAnyOrder(RestrictionKind.PERMANENT, RestrictionKind.COOLDOWN);
        assertThat(history).allSatisfy(restriction -> {
            assertThat(restriction.ownerId()).isEqualTo(ownerId);
            assertThat(restriction.fingerprint()).isEqualTo(FINGERPRINT);
            assertThat(restriction.reason()).isNotBlank();
        });
    }

    @Test
    @DisplayName("чужая пара — пустая история (допущение 3: область запрета)")
    void чужая_пара_пустая_история() {
        repository().save(ImageRestriction.permanent(UUID.randomUUID(), FINGERPRINT,
            "чужое поражение", null, NOW));

        assertThat(repository().findByOwnerAndFingerprint(UUID.randomUUID(), FINGERPRINT.value()))
            .isEmpty();
    }
}
