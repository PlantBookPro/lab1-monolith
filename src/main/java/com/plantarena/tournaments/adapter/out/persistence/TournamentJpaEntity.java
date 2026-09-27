package com.plantarena.tournaments.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * JPA-модель tournament (раздел 11); маппинг на домен — явный
 * (JpaTournamentRepository). M2M tournament ↔ tag — join table с PK по двум
 * FK (раздел 11). Мутирует — optimistic locking через @Version.
 */
@Entity
@Table(name = "tournament", schema = "tournaments")
public class TournamentJpaEntity {

    @Id
    private UUID id;

    @Column(name = "creator_id", nullable = false)
    private UUID creatorId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "type", nullable = false, length = 10)
    private String type;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "algorithm", nullable = false, length = 20)
    private String algorithm;

    @Column(name = "registration_deadline", nullable = false)
    private Instant registrationDeadline;

    @Column(name = "round_duration_seconds", nullable = false)
    private long roundDurationSeconds;

    @Column(name = "elimination_fraction", nullable = false)
    private double eliminationFraction;

    @Column(name = "min_participants", nullable = false)
    private int minParticipants;

    @Column(name = "cancel_reason", length = 30)
    private String cancelReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "tournament_tag", schema = "tournaments",
        joinColumns = @JoinColumn(name = "tournament_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<TagJpaEntity> tags = new HashSet<>();

    protected TournamentJpaEntity() {
    }

    TournamentJpaEntity(UUID id, UUID creatorId, String name, String type, String algorithm,
                        Instant registrationDeadline, long roundDurationSeconds,
                        double eliminationFraction, int minParticipants, Instant createdAt) {
        this.id = id;
        this.creatorId = creatorId;
        this.name = name;
        this.type = type;
        this.algorithm = algorithm;
        this.registrationDeadline = registrationDeadline;
        this.roundDurationSeconds = roundDurationSeconds;
        this.eliminationFraction = eliminationFraction;
        this.minParticipants = minParticipants;
        this.createdAt = createdAt;
        this.status = "DRAFT";
    }

    /** Мутации домена; id/creator/type/algorithm/created_at неизменяемы. */
    void update(String name, String description, String status,
                Instant registrationDeadline, long roundDurationSeconds,
                double eliminationFraction, int minParticipants, String cancelReason,
                Set<TagJpaEntity> tags) {
        this.name = name;
        this.description = description;
        this.status = status;
        this.registrationDeadline = registrationDeadline;
        this.roundDurationSeconds = roundDurationSeconds;
        this.eliminationFraction = eliminationFraction;
        this.minParticipants = minParticipants;
        this.cancelReason = cancelReason;
        this.tags.clear();
        this.tags.addAll(tags);
    }

    public UUID getId() {
        return id;
    }

    public UUID getCreatorId() {
        return creatorId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public Instant getRegistrationDeadline() {
        return registrationDeadline;
    }

    public long getRoundDurationSeconds() {
        return roundDurationSeconds;
    }

    public double getEliminationFraction() {
        return eliminationFraction;
    }

    public int getMinParticipants() {
        return minParticipants;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }

    public Set<TagJpaEntity> getTags() {
        return tags;
    }
}
