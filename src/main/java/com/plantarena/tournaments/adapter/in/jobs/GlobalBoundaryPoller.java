package com.plantarena.tournaments.adapter.in.jobs;

import com.plantarena.tournaments.application.port.in.AdvanceGlobalCompetitionUseCase;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
public class GlobalBoundaryPoller {

    private static final Logger log = LoggerFactory.getLogger(GlobalBoundaryPoller.class);

    private final AdvanceGlobalCompetitionUseCase advanceGlobalCompetition;
    private final Clock clock;

    public GlobalBoundaryPoller(AdvanceGlobalCompetitionUseCase advanceGlobalCompetition,
                                Clock clock) {
        this.advanceGlobalCompetition = advanceGlobalCompetition;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 2000)
    public void poll() {
        try {
            advanceGlobalCompetition.advance(clock.instant());
        } catch (RuntimeException e) {
            log.error("Цикл границ глобального турнира не удался (продолжаем): {}",
                e.getMessage());
        }
    }
}
