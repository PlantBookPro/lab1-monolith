package com.plantarena.moderation.adapter.in.jobs;

import com.plantarena.moderation.application.port.in.ProcessDueModerationJobsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Опрос due-заданий модерации: fixedDelay 2с, пачка ≤ 10 (спека итерации 4).
 * Транзакционную структуру (захват → инференс вне tx → применение) решает
 * use case; poller только вызывает его и не даёт планировщику умереть.
 */
@Component
public class ModerationJobPoller {

    private static final Logger log = LoggerFactory.getLogger(ModerationJobPoller.class);
    private static final int BATCH_SIZE = 10;

    private final ProcessDueModerationJobsUseCase processDueJobs;

    public ModerationJobPoller(ProcessDueModerationJobsUseCase processDueJobs) {
        this.processDueJobs = processDueJobs;
    }

    @Scheduled(fixedDelay = 2000)
    public void poll() {
        try {
            processDueJobs.processDue(BATCH_SIZE);
        } catch (RuntimeException e) {
            log.error("Цикл обработки заданий модерации не удался (продолжаем): {}", e.getMessage());
        }
    }
}
