package com.plantarena.tournaments.adapter.in.jobs;

import com.plantarena.tournaments.application.port.in.CloseVotingWindowUseCase;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class VotingWindowClosePoller {

    private static final int BATCH_SIZE = 10;

    private final CloseVotingWindowUseCase closeVotingWindow;
    private final Clock clock;

    @Scheduled(fixedDelay = 2000)
    public void poll() {
        try {
            closeVotingWindow.closeDue(clock.instant(), BATCH_SIZE);
        } catch (RuntimeException e) {
            log.error("Цикл закрытия окон не удался (продолжаем): {}", e.getMessage());
        }
    }
}
