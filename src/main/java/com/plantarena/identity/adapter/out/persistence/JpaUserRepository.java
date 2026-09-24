package com.plantarena.identity.adapter.out.persistence;

import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRepository;
import com.plantarena.identity.domain.UserRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация порта UserRepository на JPA (схема identity).
 * Роли ссылаются на засеянный справочник role; email уникален ограничением БД.
 */
@Repository
@Transactional
public class JpaUserRepository implements UserRepository {

    private final UserJpaRepository users;
    private final RoleJpaRepository roles;

    public JpaUserRepository(UserJpaRepository users, RoleJpaRepository roles) {
        this.users = users;
        this.roles = roles;
    }

    @Override
    public User save(User user) {
        UserJpaEntity entity = users.findById(user.id())
            .orElseGet(() -> new UserJpaEntity(user.id(), user.email().value()));
        entity.update(user.displayName(), user.passwordHash(), user.status(),
            latitudeOf(user), longitudeOf(user));
        entity.roles().clear();
        user.roles().forEach(role -> entity.roles().add(roleEntity(role)));
        return UserMapper.toDomain(users.saveAndFlush(entity));
    }

    @Override
    public Optional<User> findById(UUID id) {
        return users.findById(id).map(UserMapper::toDomain);
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        return users.findByEmail(email.value()).map(UserMapper::toDomain);
    }

    @Override
    public List<User> findAll(int offset, int limit) {
        // offset всегда page-aligned (PaginationParams: offset = page * size)
        return users.findAll(PageRequest.of(offset / limit, limit)).stream()
            .map(UserMapper::toDomain)
            .toList();
    }

    @Override
    public long count() {
        return users.count();
    }

    private RoleJpaEntity roleEntity(UserRole role) {
        return roles.findByCode(role)
            .orElseThrow(() -> new IllegalStateException(
                "Роль отсутствует в справочнике identity.role: " + role));
    }

    private Double latitudeOf(User user) {
        return user.location() == null ? null : user.location().latitude();
    }

    private Double longitudeOf(User user) {
        return user.location() == null ? null : user.location().longitude();
    }
}
