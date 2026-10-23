package com.plantarena.tournaments.adapter.out.abuse;

import com.plantarena.tournaments.application.port.out.AbuseSignals;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;


@Component
@Slf4j
public class LoggingAbuseSignals implements AbuseSignals {

    @Override
    public void signal(String action, String details) {
        log.warn("Подозрительное действие: {} ({})", action, details);
    }
}
