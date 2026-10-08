package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;


public interface RevokeInvitationUseCase {

    void revoke(CurrentActor actor, UUID tournamentId, UUID invitationId);
}
