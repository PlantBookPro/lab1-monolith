package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.InvitationRepositoryContractTest;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.TournamentRepository;

/** Фейк честен контракту InvitationRepository (UNIQUE-пара — как БД). */
class InMemoryInvitationRepositoryContractTest extends InvitationRepositoryContractTest {

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryInvitationRepository invitations =
        new InMemoryInvitationRepository(tournaments);

    @Override
    protected InvitationRepository repository() {
        return invitations;
    }

    @Override
    protected TournamentRepository tournaments() {
        return tournaments;
    }
}
