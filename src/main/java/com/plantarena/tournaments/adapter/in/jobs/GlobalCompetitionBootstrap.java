package com.plantarena.tournaments.adapter.in.jobs;

import com.plantarena.tournaments.application.port.in.EnsureGlobalCompetitionUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;


@Component
public class GlobalCompetitionBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GlobalCompetitionBootstrap.class);

    private final EnsureGlobalCompetitionUseCase ensureGlobalCompetition;

    public GlobalCompetitionBootstrap(EnsureGlobalCompetitionUseCase ensureGlobalCompetition) {
        this.ensureGlobalCompetition = ensureGlobalCompetition;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureGlobalCompetition.ensure();
        log.info("Глобальный турнир гарантирован (id фиксирован, дубликатов нет)");
    }
}
