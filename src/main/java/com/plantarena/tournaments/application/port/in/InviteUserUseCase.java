package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.InvitationData;
import java.util.UUID;

/** Пригласить пользователя (организатор, до дедлайна; дубль пары — 409). */
public interface InviteUserUseCase {

    InvitationData invite(CurrentActor actor, UUID tournamentId, UUID userId);
}
