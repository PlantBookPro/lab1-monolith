package com.plantarena.tournaments.adapter.out.identity;

import com.plantarena.tournaments.api.client.IdentityDirectoryClient;
import com.plantarena.tournaments.application.port.out.ParticipantDirectoryGateway;
import feign.FeignException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HttpParticipantDirectory implements ParticipantDirectoryGateway {

    private final IdentityDirectoryClient identityClient;

    @Override
    public boolean isKnownUser(UUID userId) {
        try {
            return identityClient.getProfile(userId).active();
        } catch (FeignException.NotFound e) {
            return false;
        }
    }
}
