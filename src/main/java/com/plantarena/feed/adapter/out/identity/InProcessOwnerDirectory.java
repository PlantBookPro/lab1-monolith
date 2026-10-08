package com.plantarena.feed.adapter.out.identity;

import com.plantarena.feed.application.port.out.OwnerDirectory;
import com.plantarena.identity.api.UserDirectory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;


@Component
public class InProcessOwnerDirectory implements OwnerDirectory {

    private final UserDirectory userDirectory;

    public InProcessOwnerDirectory(UserDirectory userDirectory) {
        this.userDirectory = userDirectory;
    }

    @Override
    public Optional<OwnerView> findOwner(UUID userId) {
        return userDirectory.findById(userId)
            .map(user -> new OwnerView(user.id(), user.displayName()));
    }
}
