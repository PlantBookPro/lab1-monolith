package com.plantarena.moderation.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Агрегат ModerationJob: переходы, инварианты, backoff без лимита с капом")
class ModerationJobTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @Test
    @DisplayName("фабрика: NEW, attempts=0, nextAttemptAt=now, без результата")
    void фабрика_new() {
        ModerationJob job = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);

        assertThat(job.status()).isEqualTo(ModerationJobStatus.NEW);
        assertThat(job.attempts()).isZero();
        assertThat(job.nextAttemptAt()).isEqualTo(NOW);
        assertThat(job.startedAt()).isNull();
        assertThat(job.completedAt()).isNull();
        assertThat(job.modelVersion()).isNull();
        assertThat(job.confidence()).isNull();
        assertThat(job.reasonCode()).isNull();
        assertThat(job.createdAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("claim: NEW → IN_PROGRESS, attempts++, startedAt первой попытки")
    void claim_из_new() {
        ModerationJob job = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);

        job.claim(NOW.plusSeconds(5));

        assertThat(job.status()).isEqualTo(ModerationJobStatus.IN_PROGRESS);
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(job.startedAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    @DisplayName("claim: RETRY → IN_PROGRESS (повторная попытка), startedAt не перезаписывается")
    void claim_из_retry() {
        ModerationJob job = claimedThenRetried();

        job.claim(NOW.plusSeconds(10));

        assertThat(job.status()).isEqualTo(ModerationJobStatus.IN_PROGRESS);
        assertThat(job.attempts()).isEqualTo(2);
        assertThat(job.startedAt()).isEqualTo(NOW); // первая попытка
    }

    @Test
    @DisplayName("claim из DONE невозможен: DONE терминален")
    void claim_из_done_невозможен() {
        ModerationJob job = succeeded();

        assertThatThrownBy(() -> job.claim(NOW.plusSeconds(60)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("succeed: IN_PROGRESS → DONE, результат обязателен")
    void succeed_заполняет_результат() {
        ModerationJob job = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        job.claim(NOW);

        job.succeed("mobilenetv2-1.0-onnx-imagenet/plant-classes-v1", 0.87f,
            ModerationReasonCode.PLANT_DETECTED, NOW.plusSeconds(1));

        assertThat(job.status()).isEqualTo(ModerationJobStatus.DONE);
        assertThat(job.modelVersion()).isEqualTo("mobilenetv2-1.0-onnx-imagenet/plant-classes-v1");
        assertThat(job.confidence()).isEqualTo(0.87f);
        assertThat(job.reasonCode()).isEqualTo(ModerationReasonCode.PLANT_DETECTED);
        assertThat(job.completedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    @DisplayName("succeed: только из IN_PROGRESS; confidence ∈ [0..1]; STALE запрещён")
    void succeed_инварианты() {
        ModerationJob newJob = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        assertThatThrownBy(() -> newJob.succeed("m", 0.5f,
            ModerationReasonCode.PLANT_DETECTED, NOW))
            .isInstanceOf(IllegalStateException.class);

        ModerationJob claimed = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        claimed.claim(NOW);
        assertThatThrownBy(() -> claimed.succeed("m", 1.5f,
            ModerationReasonCode.PLANT_DETECTED, NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claimed.succeed("m", 0.5f, ModerationReasonCode.STALE, NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claimed.succeed(" ", 0.5f,
            ModerationReasonCode.PLANT_DETECTED, NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("retry: IN_PROGRESS → RETRY, backoff 1с → 2с → 4с … кап 1ч")
    void retry_backoff() {
        ModerationJob job = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        job.claim(NOW);
        job.retry(NOW);
        assertThat(job.status()).isEqualTo(ModerationJobStatus.RETRY);
        assertThat(job.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofSeconds(1)));

        job.claim(NOW.plusSeconds(1));
        job.retry(NOW.plusSeconds(1));
        assertThat(job.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofSeconds(3)));

        job.claim(NOW.plusSeconds(3));
        job.retry(NOW.plusSeconds(3));
        assertThat(job.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofSeconds(7)));
    }

    @Test
    @DisplayName("backoff: экспонента с капом 1ч, без переполнения")
    void backoff_кап() {
        assertThat(ModerationJob.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(ModerationJob.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(ModerationJob.backoff(12)).isEqualTo(Duration.ofSeconds(2048));
        assertThat(ModerationJob.backoff(13)).isEqualTo(Duration.ofHours(1));
        assertThat(ModerationJob.backoff(100)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("retry из NEW/DONE невозможен")
    void retry_только_из_in_progress() {
        ModerationJob newJob = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        assertThatThrownBy(() -> newJob.retry(NOW))
            .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> succeeded().retry(NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("completeStale: IN_PROGRESS → DONE с reasonCode=STALE (устаревший результат)")
    void completeStale() {
        ModerationJob job = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        job.claim(NOW);

        job.completeStale(NOW.plusSeconds(2));

        assertThat(job.status()).isEqualTo(ModerationJobStatus.DONE);
        assertThat(job.reasonCode()).isEqualTo(ModerationReasonCode.STALE);
        assertThat(job.completedAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(job.modelVersion()).isNull();
    }

    private ModerationJob claimedThenRetried() {
        ModerationJob job = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        job.claim(NOW);
        job.retry(NOW);
        return job;
    }

    private ModerationJob succeeded() {
        ModerationJob job = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        job.claim(NOW);
        job.succeed("m", 0.9f, ModerationReasonCode.PLANT_DETECTED, NOW);
        return job;
    }
}
