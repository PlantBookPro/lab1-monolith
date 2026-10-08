package com.plantarena.moderation.application;

import com.plantarena.moderation.application.port.in.ProcessDueModerationJobsUseCase;
import com.plantarena.moderation.application.port.out.MediaContentGateway;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import com.plantarena.moderation.domain.ModerationJob;
import com.plantarena.moderation.domain.ModerationJobRepository;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;


@Service
public class ProcessModerationJobsService implements ProcessDueModerationJobsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessModerationJobsService.class);

    private final ModerationJobRepository jobs;
    private final MediaContentGateway mediaContent;
    private final PlantClassifier classifier;
    private final ApplyModerationResultService applyResult;
    private final Clock clock;

    public ProcessModerationJobsService(ModerationJobRepository jobs,
                                        MediaContentGateway mediaContent,
                                        PlantClassifier classifier,
                                        ApplyModerationResultService applyResult,
                                        Clock clock) {
        this.jobs = jobs;
        this.mediaContent = mediaContent;
        this.classifier = classifier;
        this.applyResult = applyResult;
        this.clock = clock;
    }

    @Override
    public int processDue(int limit) {
        List<ModerationJob> due = jobs.findDue(clock.instant(), limit);
        int processed = 0;
        for (ModerationJob candidate : due) {
            ModerationJob job = claim(candidate);
            if (job == null) {
                continue;
            }
            process(job);
            processed++;
        }
        return processed;
    }

    
    private ModerationJob claim(ModerationJob candidate) {
        try {
            candidate.claim(clock.instant());
        } catch (IllegalStateException alreadyTaken) {
            return null;
        }
        try {
            return jobs.save(candidate);
        } catch (OptimisticLockingFailureException lostRace) {
            log.debug("Задание {} захвачено другим воркером", candidate.id());
            return null;
        }
    }

    
    private void process(ModerationJob job) {
        try {
            MediaContentGateway.MediaContent content = mediaContent.loadContent(job.assetId())
                .orElseThrow(() -> new IllegalStateException(
                    "Файл задания не найден: " + job.assetId()));
            PlantClassifier.Classification result = classifier.classify(content.bytes());
            applyResult.apply(job, result);
        } catch (DecisionConflictException staleResult) {
            log.info("Устаревший результат по заявке {}: решение уже есть", job.plantId());
            applyResult.completeStale(job);
        } catch (RuntimeException technical) {
            log.warn("Попытка {} задания {} не удалась: {} — RETRY, растение остаётся PENDING",
                job.attempts(), job.id(), technical.getMessage());
            job.retry(clock.instant());
            jobs.save(job);
        }
    }
}
