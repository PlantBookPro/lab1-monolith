package com.plantarena.feed.api.client.fallback;

import com.plantarena.feed.api.client.IdentityDirectoryClient;
import org.springframework.stereotype.Component;

@Component
public class IdentityDirectoryClientFallbackFactory extends AbstractClientFallbackFactory<IdentityDirectoryClient> {

    public IdentityDirectoryClientFallbackFactory(IdentityDirectoryClientFallback fallback) {
        super(fallback);
    }
}
