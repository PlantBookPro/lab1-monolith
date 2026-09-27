package com.plantarena.moderation.application;

import com.plantarena.moderation.application.port.out.PlantClassifier;
import com.plantarena.moderation.application.port.out.PlantModerationGateway;
import com.plantarena.moderation.domain.ModerationJob;
import com.plantarena.moderation.domain.ModerationJobRepository;
import com.plantarena.moderation.domain.ModerationReasonCode;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Короткая tx применения результата (раздел 12, ADR-009): сначала
 * recordDecision (обязательное обновление заявки), затем job DONE —
 * «не ставь DONE до обязательного обновления заявки» (раздел 14).
 * Отступление «одна tx — один агрегат» описано в ADR-009 (план на лабу №2).
 */
@Service
public class ApplyModerationResultService {

    private final ModerationJobRepository jobs;
    private final PlantModerationGateway plantModeration;
    private final Clock clock;

    public ApplyModerationResultService(ModerationJobRepository jobs,
                                        PlantModerationGateway plantModeration, Clock clock) {
        this.jobs = jobs;
        this.plantModeration = plantModeration;
        this.clock = clock;
    }

    @Transactional
    public void apply(ModerationJob job, PlantClassifier.Classification result) {
        boolean plant = result.plant();
        plantModeration.recordDecision(job.plantId(),
            plant ? PlantModerationGateway.Decision.APPROVED : PlantModerationGateway.Decision.REJECTED,
            plant ? ModerationReasonCode.PLANT_DETECTED.name() : ModerationReasonCode.NOT_A_PLANT.name());
        job.succeed(result.modelVersion(), result.confidence(),
            plant ? ModerationReasonCode.PLANT_DETECTED : ModerationReasonCode.NOT_A_PLANT,
            clock.instant());
        jobs.save(job);
    }

    @Transactional
    public void completeStale(ModerationJob job) {
        job.completeStale(clock.instant());
        jobs.save(job);
    }
}
