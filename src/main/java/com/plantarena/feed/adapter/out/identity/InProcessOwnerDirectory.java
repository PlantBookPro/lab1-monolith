package com.plantarena.feed.adapter.out.identity;

import com.plantarena.feed.application.port.out.OwnerDirectory;
import com.plantarena.identity.api.UserDirectory;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class InProcessOwnerDirectory implements OwnerDirectory {

    private final UserDirectory userDirectory;

    @Override
    public Optional<OwnerView> findOwner(UUID userId) {
        return userDirectory.findById(userId)
            .map(user -> new OwnerView(user.id(), user.displayName()));
    }
}
