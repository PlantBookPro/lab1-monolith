package com.plantarena.tournaments.adapter.out.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tag", schema = "tournaments")
@Getter
@Setter
public class TagEntity {

    @Id
    private UUID id;

    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
