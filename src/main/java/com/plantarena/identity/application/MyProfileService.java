package com.plantarena.identity.application;

import com.plantarena.identity.application.port.in.MyProfileUseCase;
import com.plantarena.identity.domain.GeoPoint;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRepository;
import com.plantarena.shared.security.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Собственный профиль пользователя (/me).
 */
@Service
@Transactional
public class MyProfileService implements MyProfileUseCase {

    private final UserRepository users;
    private final IdentityAccessPolicy accessPolicy;

    public MyProfileService(UserRepository users, IdentityAccessPolicy accessPolicy) {
        this.users = users;
        this.accessPolicy = accessPolicy;
    }

    @Override
    public UserResult me(CurrentActor actor) {
        accessPolicy.requireIdentified(actor);
        return UserResult.from(findUser(actor.userId()));
    }

    @Override
    public UserResult updateLocation(CurrentActor actor, double latitude, double longitude) {
        accessPolicy.requireIdentified(actor);
        User user = findUser(actor.userId());
        user.moveTo(new GeoPoint(latitude, longitude));
        return UserResult.from(users.save(user));
    }

    private User findUser(java.util.UUID userId) {
        return users.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
    }
}
