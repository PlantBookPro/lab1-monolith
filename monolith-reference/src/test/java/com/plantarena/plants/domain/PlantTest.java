package com.plantarena.plants.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Агрегат Plant: переходы модерации и жизни, неизменяемость asset")
class PlantTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final ImageFingerprint FINGERPRINT =
        new ImageFingerprint("b".repeat(64), 1);

    private Plant newPlant() {
        return Plant.submit(UUID.randomUUID(), UUID.randomUUID(), FINGERPRINT, "Фикус", NOW);
    }

    @Test
    void подача_создаёт_PENDING_ALIVE_растение() {
        Plant plant = newPlant();

        assertThat(plant.moderationStatus()).isEqualTo(ModerationStatus.PENDING);
        assertThat(plant.lifeStatus()).isEqualTo(LifeStatus.ALIVE);
        assertThat(plant.createdAt()).isEqualTo(NOW);
        assertThat(plant.diedAt()).isNull();
        assertThat(plant.archivedAt()).isNull();
        assertThat(plant.moderationReason()).isNull();
    }

    @Test
    void название_валидируется_доменом() {
        assertThatThrownBy(() -> Plant.submit(UUID.randomUUID(), UUID.randomUUID(),
            FINGERPRINT, "   ", NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Plant.submit(UUID.randomUUID(), UUID.randomUUID(),
            FINGERPRINT, "д".repeat(101), NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void одобрение_и_отклонение_переводят_из_PENDING() {
        Plant approved = newPlant();
        assertThat(approved.applyDecision(ModerationStatus.APPROVED, null)).isTrue();
        assertThat(approved.moderationStatus()).isEqualTo(ModerationStatus.APPROVED);

        Plant rejected = newPlant();
        assertThat(rejected.applyDecision(ModerationStatus.REJECTED, "не растение")).isTrue();
        assertThat(rejected.moderationReason()).isEqualTo("не растение");
    }

    @Test
    void повтор_того_же_решения_идемпотентен() {
        Plant plant = newPlant();
        plant.applyDecision(ModerationStatus.APPROVED, null);

        assertThat(plant.applyDecision(ModerationStatus.APPROVED, null)).isFalse();
        assertThat(plant.moderationStatus()).isEqualTo(ModerationStatus.APPROVED);
    }

    @Test
    void конфликтующее_решение_по_решённой_заявке_отклоняется() {
        Plant plant = newPlant();
        plant.applyDecision(ModerationStatus.APPROVED, null);

        assertThatThrownBy(() -> plant.applyDecision(ModerationStatus.REJECTED, "опоздало"))
            .isInstanceOf(PlantAlreadyDecidedException.class);
    }

    @Test
    void гибель_необратима_и_фиксирует_момент() {
        Plant plant = newPlant();

        assertThat(plant.die(NOW.plusSeconds(60))).isTrue();
        assertThat(plant.lifeStatus()).isEqualTo(LifeStatus.DEAD);
        assertThat(plant.diedAt()).isEqualTo(NOW.plusSeconds(60));

        assertThat(plant.die(NOW.plusSeconds(120))).isFalse(); // повтор — no-op
        assertThat(plant.diedAt()).isEqualTo(NOW.plusSeconds(60)); // момент не смещается
    }

    @Test
    void архивация_скрывает_и_идемпотентна() {
        Plant plant = newPlant();

        assertThat(plant.archive(NOW.plusSeconds(30))).isTrue();
        assertThat(plant.archivedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(plant.archive(NOW.plusSeconds(60))).isFalse();
    }

    @Test
    void переименование_меняет_только_название() {
        Plant plant = newPlant();
        UUID assetIdBefore = plant.assetId();
        UUID ownerBefore = plant.ownerId();

        plant.rename("  Новое имя  ");

        assertThat(plant.title()).isEqualTo("Новое имя");
        assertThat(plant.assetId()).isEqualTo(assetIdBefore); // asset не меняется после подачи
        assertThat(plant.ownerId()).isEqualTo(ownerBefore);
        assertThat(plant.moderationStatus()).isEqualTo(ModerationStatus.PENDING);
        assertThat(plant.lifeStatus()).isEqualTo(LifeStatus.ALIVE);
    }

    @Test
    void восстановление_требует_diedAt_для_DEAD() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> Plant.restore(id, UUID.randomUUID(), UUID.randomUUID(),
            FINGERPRINT, "Фикус", ModerationStatus.PENDING, null, LifeStatus.DEAD,
            NOW, null, null, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
