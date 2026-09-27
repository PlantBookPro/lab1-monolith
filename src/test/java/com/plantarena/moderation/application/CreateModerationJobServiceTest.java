package com.plantarena.moderation.application;

import com.plantarena.moderation.application.support.InMemoryModerationJobRepository;
import com.plantarena.moderation.domain.ModerationJob;
import com.plantarena.moderation.domain.ModerationJobStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Событие PlantSubmitted создаёт задание NEW (идемпотентно)")
class CreateModerationJobServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final InMemoryModerationJobRepository jobs = new InMemoryModerationJobRepository();
    private final CreateModerationJobService service =
        new CreateModerationJobService(jobs, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("первое событие — задание NEW, nextAttemptAt=now")
    void событие_создаёт_задание() {
        UUID plantId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();

        service.onPlantSubmitted(plantId, assetId);

        ModerationJob job = jobs.findLatestByPlantId(plantId).orElseThrow();
        assertThat(job.status()).isEqualTo(ModerationJobStatus.NEW);
        assertThat(job.assetId()).isEqualTo(assetId);
        assertThat(job.attempts()).isZero();
        assertThat(job.nextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("повторная доставка события — no-op (одно задание на заявку)")
    void повтор_события_идемпотентен() {
        UUID plantId = UUID.randomUUID();

        service.onPlantSubmitted(plantId, UUID.randomUUID());
        service.onPlantSubmitted(plantId, UUID.randomUUID());

        assertThat(jobs.jobs).hasSize(1);
    }
}
