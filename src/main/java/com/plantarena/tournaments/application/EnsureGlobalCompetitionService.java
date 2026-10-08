package com.plantarena.tournaments.application;

import com.plantarena.tournaments.application.port.in.EnsureGlobalCompetitionUseCase;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


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
