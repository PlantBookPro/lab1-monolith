package com.plantarena.tournaments.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;


@Entity
@Table(name = "tag", schema = "tournaments")
public class TagJpaEntity {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false, length = 50, unique = true)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TagJpaEntity() {
    }

    TagJpaEntity(UUID id, String name, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
    }

    void update(String name) {
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
