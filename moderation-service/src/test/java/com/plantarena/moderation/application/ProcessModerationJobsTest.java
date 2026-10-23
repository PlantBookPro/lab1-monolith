package com.plantarena.moderation.application;

import com.plantarena.moderation.application.port.out.ClassifierUnavailableException;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import com.plantarena.moderation.application.support.FakeMediaContentGateway;
import com.plantarena.moderation.application.support.FakePlantModerationGateway;
import com.plantarena.moderation.application.support.InMemoryModerationJobRepository;
import com.plantarena.moderation.application.support.ScriptedClassifier;
import com.plantarena.moderation.domain.ModerationJob;
import com.plantarena.moderation.domain.ModerationJobStatus;
import com.plantarena.moderation.domain.ModerationReasonCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Обработка due-заданий: успех/ошибка/конфликт, due-фильтр (раздел 12)")
class ProcessModerationJobsTest {

    private final InMemoryModerationJobRepository jobs = new InMemoryModerationJobRepository();
    private final FakeMediaContentGateway media = new FakeMediaContentGateway();
    private final ScriptedClassifier classifier = new ScriptedClassifier();
    private final FakePlantModerationGateway plants = new FakePlantModerationGateway();
    private final SettableClock clock = new SettableClock();

    private final ApplyModerationResultService applyResult =
        new ApplyModerationResultService(jobs, plants, clock);
    private final ProcessModerationJobsService service =
        new ProcessModerationJobsService(jobs, media, classifier, applyResult, clock);

    @Test
    @DisplayName("успех: plant=true → job DONE, решение APPROVED/PLANT_DETECTED, attempts=1")
    void успех_одобрение() {
        UUID plantId = UUID.randomUUID();
        classifier.result = new PlantClassifier.Classification(true, 0.87f, "m-v1");
        submitDueJob(plantId);

        int processed = service.processDue(10);

        assertThat(processed).isEqualTo(1);
        ModerationJob job = jobs.jobs.values().iterator().next();
        assertThat(job.status()).isEqualTo(ModerationJobStatus.DONE);
        assertThat(job.reasonCode()).isEqualTo(ModerationReasonCode.PLANT_DETECTED);
        assertThat(job.modelVersion()).isEqualTo("m-v1");
        assertThat(job.confidence()).isEqualTo(0.87f);
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(plants.decisions).containsExactly(
            new FakePlantModerationGateway.Recorded(plantId,
                com.plantarena.moderation.application.port.out.PlantModerationGateway.Decision.APPROVED,
                "PLANT_DETECTED"));
    }

    @Test
    @DisplayName("успех: plant=false → REJECTED/NOT_A_PLANT")
    void не_растение_отклонение() {
        UUID plantId = UUID.randomUUID();
        classifier.result = new PlantClassifier.Classification(false, 0.99f, "m-v1");
        submitDueJob(plantId);

        service.processDue(10);

        assertThat(plants.decisions).containsExactly(
            new FakePlantModerationGateway.Recorded(plantId,
                com.plantarena.moderation.application.port.out.PlantModerationGateway.Decision.REJECTED,
                "NOT_A_PLANT"));
        assertThat(jobs.jobs.values().iterator().next().reasonCode())
            .isEqualTo(ModerationReasonCode.NOT_A_PLANT);
    }

    @Test
    @DisplayName("техническая ошибка классификатора → RETRY с backoff, решение не применяется")
    void ошибка_классификатора_retry() {
        submitDueJob(UUID.randomUUID());
        classifier.failure = new ClassifierUnavailableException("модель не найдена");

        service.processDue(10);

        ModerationJob job = jobs.jobs.values().iterator().next();
        assertThat(job.status()).isEqualTo(ModerationJobStatus.RETRY);
        assertThat(job.nextAttemptAt()).isEqualTo(clock.instant().plus(Duration.ofSeconds(1)));
        assertThat(plants.decisions).isEmpty();

        // due-фильтр: до nextAttemptAt задание не захватывается повторно
        clock.advance(Duration.ofMillis(500));
        assertThat(service.processDue(10)).isZero();

        // после backoff — вторая попытка (attempts=2), при успехе — DONE
        classifier.failure = null;
        clock.advance(Duration.ofSeconds(1));
        service.processDue(10);
        assertThat(job.status()).isEqualTo(ModerationJobStatus.DONE);
        assertThat(job.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("файл исчез → техническая ошибка → RETRY, растение не тронуто")
    void файл_исчез_retry() {
        submitDueJob(UUID.randomUUID());
        media.present = false;

        service.processDue(10);

        assertThat(jobs.jobs.values().iterator().next().status())
            .isEqualTo(ModerationJobStatus.RETRY);
        assertThat(plants.decisions).isEmpty();
    }

    @Test
    @DisplayName("конфликт решения → job DONE с reasonCode=STALE")
    void конфликт_решения_stale() {
        submitDueJob(UUID.randomUUID());
        plants.conflict = new DecisionConflictException("решение уже зафиксировано: APPROVED");

        service.processDue(10);

        ModerationJob job = jobs.jobs.values().iterator().next();
        assertThat(job.status()).isEqualTo(ModerationJobStatus.DONE);
        assertThat(job.reasonCode()).isEqualTo(ModerationReasonCode.STALE);
    }

    @Test
    @DisplayName("due-фильтр: задание из будущего не обрабатывается")
    void due_фильтр_будущее() {
        UUID plantId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        jobs.save(ModerationJob.create(plantId, assetId, clock.instant().plus(Duration.ofHours(1))));

        assertThat(service.processDue(10)).isZero();
        assertThat(jobs.findLatestByPlantId(plantId).orElseThrow().status())
            .isEqualTo(ModerationJobStatus.NEW);
    }

    private void submitDueJob(UUID plantId) {
        jobs.save(ModerationJob.create(plantId, UUID.randomUUID(), clock.instant()));
    }

    /** Управляемые часы: домен получает время аргументом (раздел 14). */
    private static final class SettableClock extends Clock {

        private Instant now = Instant.parse("2026-09-27T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
