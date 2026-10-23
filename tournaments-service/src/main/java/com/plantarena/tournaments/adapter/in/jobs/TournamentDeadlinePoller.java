package com.plantarena.tournaments.adapter.in.jobs;

import com.plantarena.tournaments.application.port.in.StartTournamentUseCase;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@Slf4j
@RequiredArgsConstructor
public class TournamentDeadlinePoller {

    private static final int BATCH_SIZE = 10;

    private final StartTournamentUseCase startTournament;
    private final Clock clock;

    @Scheduled(fixedDelay = 2000)
    public void poll() {
        try {
            startTournament.startDue(clock.instant(), BATCH_SIZE);
        } catch (RuntimeException e) {
            log.error("Цикл обработки дедлайнов турниров не удался (продолжаем): {}",
                e.getMessage());
        }
    }
}
