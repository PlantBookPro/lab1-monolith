package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.InvitationData;
import java.util.UUID;

/**
 * Принять приглашение с растением (адресат, до дедлайна): резерв изображения
 * в той же tx (ADR-010); APPROVED → READY, иначе ACCEPTED_PENDING_MODERATION.
 */
public interface AcceptInvitationUseCase {

    InvitationData accept(CurrentActor actor, UUID invitationId, UUID plantId);
}
