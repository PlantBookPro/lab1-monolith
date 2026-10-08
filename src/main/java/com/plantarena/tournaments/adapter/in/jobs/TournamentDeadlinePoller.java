package com.plantarena.tournaments.adapter.in.jobs;

import com.plantarena.tournaments.application.port.in.StartTournamentUseCase;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
public class TournamentDeadlinePoller {

    private static final Logger log = LoggerFactory.getLogger(TournamentDeadlinePoller.class);
    private static final int BATCH_SIZE = 10;

    private final StartTournamentUseCase startTournament;
    private final Clock clock;

    public TournamentDeadlinePoller(StartTournamentUseCase startTournament, Clock clock) {
        this.startTournament = startTournament;
        this.clock = clock;
    }

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
