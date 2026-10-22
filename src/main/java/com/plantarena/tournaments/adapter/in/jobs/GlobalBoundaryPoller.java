package com.plantarena.tournaments.adapter.in.jobs;

import com.plantarena.tournaments.application.port.in.AdvanceGlobalCompetitionUseCase;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@Slf4j
@RequiredArgsConstructor
public class GlobalBoundaryPoller {

    private final AdvanceGlobalCompetitionUseCase advanceGlobalCompetition;
    private final Clock clock;

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
