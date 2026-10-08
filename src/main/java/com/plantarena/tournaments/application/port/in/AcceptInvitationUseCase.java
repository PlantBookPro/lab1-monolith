package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.InvitationData;
import java.util.UUID;


public interface AcceptInvitationUseCase {

    InvitationData accept(CurrentActor actor, UUID invitationId, UUID plantId);
}
