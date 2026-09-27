package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.InvitationData;
import java.util.List;
import java.util.UUID;

/** Списки приглашений: организатору — турнира, пользователю — свои (раздел 13). */
public interface ListInvitationsUseCase {

    InvitationListResult list(CurrentActor actor, UUID tournamentId, int page, int size);

    InvitationListResult listMine(CurrentActor actor, int page, int size);

    record InvitationListResult(List<InvitationData> items, long total) {
    }
}
