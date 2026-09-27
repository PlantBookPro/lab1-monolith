package com.plantarena.tournaments.adapter.in.jobs;

import com.plantarena.tournaments.application.port.in.CloseVotingWindowUseCase;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Опрос просроченных окон: fixedDelay 2с, пачка ≤ 10. Тот же use case, что
 * demo-ручка (разделы 7, 12.3); per-window tx и устойчивость к сбоям решает
 * closeDue. Планировщик уже включён (ModerationWiringConfig).
 */
@Component
public class VotingWindowClosePoller {

    private static final Logger log = LoggerFactory.getLogger(VotingWindowClosePoller.class);
    private static final int BATCH_SIZE = 10;

    private final CloseVotingWindowUseCase closeVotingWindow;
    private final Clock clock;

    public VotingWindowClosePoller(CloseVotingWindowUseCase closeVotingWindow, Clock clock) {
        this.closeVotingWindow = closeVotingWindow;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 2000)
    public void poll() {
        try {
            closeVotingWindow.closeDue(clock.instant(), BATCH_SIZE);
        } catch (RuntimeException e) {
            log.error("Цикл закрытия окон не удался (продолжаем): {}", e.getMessage());
        }
    }
}
