package com.plantarena.moderation.adapter.in.jobs;

import com.plantarena.moderation.application.port.in.ProcessDueModerationJobsUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
@Slf4j
public class ModerationJobPoller {

    private static final int BATCH_SIZE = 10;

    private final ProcessDueModerationJobsUseCase processDueJobs;

    @Scheduled(fixedDelay = 2000)
    public void poll() {
        try {
            processDueJobs.processDue(BATCH_SIZE);
        } catch (RuntimeException e) {
            log.error("Цикл обработки заданий модерации не удался (продолжаем): {}", e.getMessage());
        }
    }
}
