package com.plantarena.tournaments.api.client.fallback;

import com.plantarena.tournaments.api.client.IdentityDirectoryClient;
import org.springframework.stereotype.Component;

@Component
public class IdentityDirectoryClientFallbackFactory extends AbstractClientFallbackFactory<IdentityDirectoryClient> {

    public IdentityDirectoryClientFallbackFactory(IdentityDirectoryClientFallback fallback) {
        super(fallback);
    }
}
