package com.plantarena.tournaments.application;

import com.plantarena.tournaments.application.port.in.EnsureGlobalCompetitionUseCase;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Идемпотентное создание единственной записи глобального турнира (раздел 8,
 * ADR-012): фиксированный id, статус RUNNING навсегда, creator — системный
 * UUID. Вызывается bootstrap-раннером при старте; повтор — no-op.
 */
@Service
public class EnsureGlobalCompetitionService implements EnsureGlobalCompetitionUseCase {

    private final TournamentRepository tournaments;
    private final Clock clock;

    public EnsureGlobalCompetitionService(TournamentRepository tournaments, Clock clock) {
        this.tournaments = tournaments;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void ensure() {
        if (tournaments.findById(GlobalCompetitionId.VALUE).isPresent()) {
            return;
        }
        tournaments.save(Tournament.global(GlobalCompetitionId.VALUE, GlobalCompetitionId.VALUE,
            clock.instant()));
    }
}
