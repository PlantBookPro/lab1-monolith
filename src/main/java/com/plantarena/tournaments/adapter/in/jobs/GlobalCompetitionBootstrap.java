package com.plantarena.tournaments.adapter.in.jobs;

import com.plantarena.tournaments.application.port.in.EnsureGlobalCompetitionUseCase;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;


@Component
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class GlobalCompetitionBootstrap implements ApplicationRunner {

    private final EnsureGlobalCompetitionUseCase ensureGlobalCompetition;

    @Override
    public void run(ApplicationArguments args) {
        ensureGlobalCompetition.ensure();
        log.info("Глобальный турнир гарантирован (id фиксирован, дубликатов нет)");
    }
}
