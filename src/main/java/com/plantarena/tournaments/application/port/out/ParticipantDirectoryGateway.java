package com.plantarena.tournaments.application.port.out;

import java.util.UUID;

/**
 * Выходной порт tournaments: проверка известного активного пользователя при
 * приглашении. Адаптер — ACL над identity.api.UserDirectory.
 */
public interface ParticipantDirectoryGateway {

    boolean isKnownUser(UUID userId);
}
