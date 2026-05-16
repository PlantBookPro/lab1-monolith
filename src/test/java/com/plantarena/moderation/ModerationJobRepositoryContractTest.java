package com.plantarena.moderation;

import com.plantarena.moderation.domain.ModerationJob;
import com.plantarena.moderation.domain.ModerationJobRepository;
import com.plantarena.moderation.domain.ModerationJobStatus;
import com.plantarena.moderation.domain.ModerationReasonCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт ModerationJobRepository (раздел 14.2): одинаковые гарантии у
 * in-memory фейка и JPA + PostgreSQL. @Transactional обязателен на базовом
 * классе (урок итерации 2).
 */
@DisplayName("Контракт ModerationJobRepository")
@Transactional
public abstract class ModerationJobRepositoryContractTest {

    protected static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    protected abstract ModerationJobRepository repository();

    private ModerationJob newJob(UUID plantId, Instant nextAttemptAt) {
        return ModerationJob.create(plantId, UUID.randomUUID(), nextAttemptAt);
    }

    @Test
    @DisplayName("сохранение и чтение по id: все поля")
    void сохранение_и_чтение_по_id() {
        ModerationJob job = newJob(UUID.randomUUID(), NOW);

        repository().save(job);
        ModerationJob loaded = repository().findById(job.id()).orElseThrow();

        assertThat(loaded.id()).isEqualTo(job.id());
        assertThat(loaded.plantId()).isEqualTo(job.plantId());
        assertThat(loaded.assetId()).isEqualTo(job.assetId());
        assertThat(loaded.status()).isEqualTo(ModerationJobStatus.NEW);
        assertThat(loaded.attempts()).isZero();
        assertThat(loaded.nextAttemptAt()).isEqualTo(NOW);
        assertThat(loaded.createdAt()).isEqualTo(NOW);
        assertThat(loaded.modelVersion()).isNull();
        assertThat(loaded.confidence()).isNull();
        assertThat(loaded.reasonCode()).isNull();
        assertThat(loaded.startedAt()).isNull();
        assertThat(loaded.completedAt()).isNull();
    }

    @Test
    @DisplayName("мутации claim/succeed/retry переживают сохранение")
    void мутации_переживают_сохранение() {
        ModerationJob job = newJob(UUID.randomUUID(), NOW);
        repository().save(job);

        job.claim(NOW.plusSeconds(1));
        job.succeed("m-v1", 0.5f, ModerationReasonCode.NOT_A_PLANT, NOW.plusSeconds(2));
        repository().save(job);

        ModerationJob loaded = repository().findById(job.id()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(ModerationJobStatus.DONE);
        assertThat(loaded.attempts()).isEqualTo(1);
        assertThat(loaded.modelVersion()).isEqualTo("m-v1");
        assertThat(loaded.confidence()).isEqualTo(0.5f);
        assertThat(loaded.reasonCode()).isEqualTo(ModerationReasonCode.NOT_A_PLANT);
        assertThat(loaded.startedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(loaded.completedAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    @DisplayName("несуществующий id — пустой результат")
    void несуществующий_id_пустой_результат() {
        assertThat(repository().findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("findDue: NEW/RETRY с прошедшим nextAttemptAt, по возрастанию, лимит")
    void findDue_фильтр_порядок_лимит() {
        UUID plantId = UUID.randomUUID();
        ModerationJob future = newJob(plantId, NOW.plusSeconds(3600));
        ModerationJob retryDue = newJob(plantId, NOW.minusSeconds(60));
        retryDue.claim(NOW.minusSeconds(50));
        retryDue.retry(NOW.minusSeconds(50)); // RETRY, nextAttemptAt = NOW-49
        ModerationJob newDue = newJob(plantId, NOW.minusSeconds(5));
        repository().save(future);
        repository().save(retryDue);
        repository().save(newDue);

        List<ModerationJob> due = repository().findDue(NOW, 10);

        assertThat(due).extracting(ModerationJob::id)
            .containsExactly(retryDue.id(), newDue.id()); // по nextAttemptAt
        assertThat(due).extracting(ModerationJob::status)
            .containsOnly(ModerationJobStatus.NEW, ModerationJobStatus.RETRY);

        assertThat(repository().findDue(NOW, 1)).hasSize(1);
        assertThat(repository().findDue(NOW.minusSeconds(3600), 10)).isEmpty();
    }

    @Test
    @DisplayName("findDue: IN_PROGRESS и DONE не due")
    void findDue_не_включает_занятые_и_завершённые() {
        ModerationJob inProgress = newJob(UUID.randomUUID(), NOW);
        inProgress.claim(NOW);
        ModerationJob done = newJob(UUID.randomUUID(), NOW);
        done.claim(NOW);
        done.succeed("m", 0.9f, ModerationReasonCode.PLANT_DETECTED, NOW);
        repository().save(inProgress);
        repository().save(done);

        assertThat(repository().findDue(NOW, 10)).isEmpty();
    }

    @Test
    @DisplayName("findLatestByPlantId: последнее по createdAt")
    void findLatestByPlantId() {
        UUID plantId = UUID.randomUUID();
        ModerationJob first = ModerationJob.restore(UUID.randomUUID(), plantId,
            UUID.randomUUID(), ModerationJobStatus.DONE, 1, NOW, "m", 0.9f,
            ModerationReasonCode.PLANT_DETECTED, NOW, NOW, NOW.minusSeconds(60), 0);
        ModerationJob second = newJob(plantId, NOW);
        repository().save(first);
        repository().save(second);

        assertThat(repository().findLatestByPlantId(plantId)).contains(second);
        assertThat(repository().findLatestByPlantId(UUID.randomUUID())).isEmpty();
    }
}
