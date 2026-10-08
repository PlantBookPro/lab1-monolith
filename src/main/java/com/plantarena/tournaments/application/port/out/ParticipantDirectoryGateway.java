package com.plantarena.tournaments.application.port.out;

import java.util.UUID;


public interface ParticipantDirectoryGateway {

    boolean isKnownUser(UUID userId);
}
