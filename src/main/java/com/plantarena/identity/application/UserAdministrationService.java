package com.plantarena.identity.application;

import com.plantarena.identity.application.port.in.UserAdministrationUseCase;
import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.PasswordHasher;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRepository;
import com.plantarena.shared.security.CurrentActor;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Служебное управление пользователями. Транзакционные границы — в application
 * (раздел 12), одна транзакция изменяет один агрегат User.
 */
@Service
@Transactional
public class UserAdministrationService implements UserAdministrationUseCase {

    private final UserRepository users;
    private final PasswordHasher passwordHasher;
    private final IdentityAccessPolicy accessPolicy;

    public UserAdministrationService(UserRepository users, PasswordHasher passwordHasher,
                                      IdentityAccessPolicy accessPolicy) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.accessPolicy = accessPolicy;
    }

    @Override
    public UserResult create(CurrentActor actor, CreateUserCommand command) {
        accessPolicy.requireUserManagement(actor);
        Email email = new Email(command.email());
        if (users.findByEmail(email).isPresent()) {
            throw new EmailAlreadyInUseException(email);
        }
        User user = User.registerUser(email, command.displayName(),
            passwordHasher.hash(command.password()));
        return UserResult.from(users.save(user));
    }

    @Override
    public UserResult get(CurrentActor actor, UUID userId) {
        accessPolicy.requireViewUser(actor, userId);
        return UserResult.from(findUser(userId));
    }

    @Override
    public UserListResult list(CurrentActor actor, int page, int size) {
        accessPolicy.requireUserManagement(actor);
        List<UserResult> items = users.findAll(page * size, size).stream()
            .map(UserResult::from)
            .toList();
        return new UserListResult(items, users.count());
    }

    @Override
    public UserResult updateDisplayName(CurrentActor actor, UUID userId, String displayName) {
        accessPolicy.requireEditProfile(actor, userId);
        User user = findUser(userId);
        user.changeDisplayName(displayName);
        return UserResult.from(users.save(user));
    }

    @Override
    public void deactivate(CurrentActor actor, UUID userId) {
        accessPolicy.requireAdmin(actor);
        User user = findUser(userId);
        user.deactivate();
        users.save(user);
    }

    @Override
    public UserResult grantModerator(CurrentActor actor, UUID userId) {
        accessPolicy.requireAdmin(actor);
        User user = findUser(userId);
        user.grantModerator();
        return UserResult.from(users.save(user));
    }

    @Override
    public UserResult revokeModerator(CurrentActor actor, UUID userId) {
        accessPolicy.requireAdmin(actor);
        User user = findUser(userId);
        user.revokeModerator();
        return UserResult.from(users.save(user));
    }

    private User findUser(UUID userId) {
        return users.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
    }
}
