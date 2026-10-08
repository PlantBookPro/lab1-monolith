package com.plantarena.identity.application;

import com.plantarena.identity.api.UserDirectory;
import com.plantarena.identity.domain.UserRepository;
import com.plantarena.identity.domain.UserStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@Transactional(readOnly = true)
public class UserDirectoryFacade implements UserDirectory {

    private final UserRepository users;

    public UserDirectoryFacade(UserRepository users) {
        this.users = users;
    }

    @Override
    public Optional<UserData> findById(UUID userId) {
        return users.findById(userId)
            .map(user -> new UserData(user.id(), user.displayName(),
                user.status() == UserStatus.ACTIVE));
    }

    @Override
    public Optional<UserLocation> findLocation(UUID userId) {
        return users.findById(userId)
            .flatMap(user -> Optional.ofNullable(user.location())
                .map(location -> new UserLocation(location.latitude(), location.longitude(),
                    user.version())));
    }
}
