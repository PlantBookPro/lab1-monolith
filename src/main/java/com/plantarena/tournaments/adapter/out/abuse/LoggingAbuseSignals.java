package com.plantarena.tournaments.adapter.out.abuse;

import com.plantarena.tournaments.application.port.out.AbuseSignals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Логирующий адаптер журнала подозрительных действий (раздел 9). */
@Component
public class LoggingAbuseSignals implements AbuseSignals {

    private static final Logger log = LoggerFactory.getLogger(LoggingAbuseSignals.class);

    @Override
    public void signal(String action, String details) {
        log.warn("Подозрительное действие: {} ({})", action, details);
    }
}
