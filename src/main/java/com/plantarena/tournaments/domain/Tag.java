package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат tournaments: тег справочника тематик (раздел 13). Простой
 * справочник — без version (единственная мутация rename, админ).
 */
public final class Tag {

    private final UUID id;
    private String name;
    private final Instant createdAt;

    private Tag(UUID id, String name, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = requireName(name);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static Tag create(String name, Instant now) {
        return new Tag(UUID.randomUUID(), name, now);
    }

    public static Tag restore(UUID id, String name, Instant createdAt) {
        return new Tag(id, name, createdAt);
    }

    public void rename(String name) {
        this.name = requireName(name);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Название тега обязательно");
        }
        return name.trim();
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
