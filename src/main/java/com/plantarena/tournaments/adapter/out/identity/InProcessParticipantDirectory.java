package com.plantarena.tournaments.adapter.out.identity;

import com.plantarena.identity.api.UserDirectory;
import com.plantarena.tournaments.application.port.out.ParticipantDirectoryGateway;
import java.util.UUID;
import org.springframework.stereotype.Component;


@Component
public class InProcessParticipantDirectory implements ParticipantDirectoryGateway {

    private final UserDirectory userDirectory;

    public InProcessParticipantDirectory(UserDirectory userDirectory) {
        this.userDirectory = userDirectory;
    }

    @Override
    public boolean isKnownUser(UUID userId) {
        return userDirectory.findById(userId)
            .map(UserDirectory.UserData::active)
            .orElse(false);
    }
}
