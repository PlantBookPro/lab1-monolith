package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.application.port.out.ParticipantDirectoryGateway;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Фейк каталога участников: известные пользователи. */
public class FakeParticipantDirectoryGateway implements ParticipantDirectoryGateway {

    public final Set<UUID> knownUsers = new HashSet<>();

    @Override
    public boolean isKnownUser(UUID userId) {
        return knownUsers.contains(userId);
    }
}
