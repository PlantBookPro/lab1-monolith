package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.InvitationData;
import java.util.UUID;


public interface InviteUserUseCase {

    InvitationData invite(CurrentActor actor, UUID tournamentId, UUID userId);
}
