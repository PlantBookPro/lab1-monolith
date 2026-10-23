package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.InvitationData;
import java.util.UUID;


public interface DeclineInvitationUseCase {

    InvitationData decline(CurrentActor actor, UUID invitationId);
}
