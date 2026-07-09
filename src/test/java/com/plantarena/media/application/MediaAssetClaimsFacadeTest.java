package com.plantarena.media.application;

import com.plantarena.media.api.AssetInUseException;
import com.plantarena.media.application.support.InMemoryAssetClaimRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Контракт MediaAssetClaims: задействованность и публичность файлов")
class MediaAssetClaimsFacadeTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    private final InMemoryAssetClaimRepository claims = new InMemoryAssetClaimRepository();
    private final MediaAssetClaimsFacade facade =
        new MediaAssetClaimsFacade(claims, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void claim_занимает_файл_и_повтор_того_же_растения_обновляет_публичность() {
        UUID assetId = UUID.randomUUID();
        UUID plantId = UUID.randomUUID();

        facade.claim(assetId, plantId, false);   // подача: занят, но не публичен
        facade.claim(assetId, plantId, true);    // модерация APPROVED

        assertThat(claims.claims).hasSize(1);    // upsert, не вторая запись
        assertThat(claims.claims.get(assetId).plantId()).isEqualTo(plantId);
        assertThat(claims.claims.get(assetId).publiclyVisible()).isTrue();
        assertThat(claims.claims.get(assetId).claimedAt()).isEqualTo(NOW);
    }

    @Test
    void claim_файла_чужого_растения_конфликтует() {
        UUID assetId = UUID.randomUUID();
        facade.claim(assetId, UUID.randomUUID(), false);

        assertThatThrownBy(() -> facade.claim(assetId, UUID.randomUUID(), false))
            .isInstanceOf(AssetInUseException.class);
    }

    @Test
    void release_освобождает_файл_и_игнорирует_чужое_растение() {
        UUID assetId = UUID.randomUUID();
        UUID plantId = UUID.randomUUID();
        facade.claim(assetId, plantId, true);

        facade.release(assetId, UUID.randomUUID()); // чужое растение — no-op
        assertThat(claims.claims).hasSize(1);

        facade.release(assetId, plantId);
        assertThatCode(() -> facade.release(assetId, plantId)) // повтор доставки — no-op
            .doesNotThrowAnyException();
        assertThat(claims.claims).isEmpty();
    }
}
