package com.plantarena.feed.adapter.out.identity;

import com.plantarena.feed.api.client.IdentityDirectoryClient;
import com.plantarena.feed.application.port.out.OwnerDirectory;
import feign.FeignException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HttpOwnerDirectory implements OwnerDirectory {

    private final IdentityDirectoryClient identityClient;

    @Override
    public Optional<OwnerView> findOwner(UUID userId) {
        try {
            IdentityDirectoryClient.UserProfileView profile = identityClient.getProfile(userId);
            return Optional.of(new OwnerView(profile.id(), profile.displayName()));
        } catch (FeignException.NotFound e) {
            return Optional.empty();
        }
    }
}
