package com.plantarena.identity.application.support;

import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fake репозитория User для application-тестов (раздел 14.2).
 * Честность фейка проверяется контрактным тестом Task 5.
 */
public class InMemoryUserRepository implements UserRepository {

    private final Map<UUID, User> usersById = new ConcurrentHashMap<>();

    @Override
    public User save(User user) {
        usersById.put(user.id(), user);
        return user;
    }

    @Override
    public Optional<User> findById(UUID id) {
        return Optional.ofNullable(usersById.get(id));
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        return usersById.values().stream()
            .filter(user -> user.email().equals(email))
            .findFirst();
    }

    @Override
    public List<User> findAll(int offset, int limit) {
        List<User> all = new ArrayList<>(usersById.values());
        all.sort(Comparator.comparing(user -> user.id().toString()));
        return all.subList(Math.min(offset, all.size()), Math.min(offset + limit, all.size()));
    }

    @Override
    public long count() {
        return usersById.size();
    }
}
