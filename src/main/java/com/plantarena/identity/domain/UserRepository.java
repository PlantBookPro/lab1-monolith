package com.plantarena.identity.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Порт репозитория агрегата User: интерфейс в domain,
 * реализация в adapter.out.persistence (раздел 5 требований).
 * offset всегда выровнен по size (page * size, PaginationParams).
 */
public interface UserRepository {

    User save(User user);

    Optional<User> findById(UUID id);

    Optional<User> findByEmail(Email email);

    List<User> findAll(int offset, int limit);

    long count();
}
