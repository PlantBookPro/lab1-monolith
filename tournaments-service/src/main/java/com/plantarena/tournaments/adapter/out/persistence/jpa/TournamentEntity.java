package com.plantarena.tournaments.adapter.out.persistence.jpa;

import com.plantarena.tournaments.domain.CancelReason;
import com.plantarena.tournaments.domain.TournamentStatus;
import com.plantarena.tournaments.domain.TournamentType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tournament", schema = "tournaments")
@Getter
@Setter
public class TournamentEntity {

    @Id
    private UUID id;

    @Column(name = "creator_id", nullable = false)
    private UUID creatorId;

    private String name;
    private String description;

    @Enumerated(EnumType.STRING)
    private TournamentType type;

    @Enumerated(EnumType.STRING)
    private TournamentStatus status;

    private String algorithm;

    @Column(name = "registration_deadline")
    private Instant registrationDeadline;

    @Convert(converter = DurationSecondsConverter.class)
    @Column(name = "round_duration_seconds")
    private Duration roundDuration;

    @Column(name = "elimination_fraction")
    private double eliminationFraction;

    @Column(name = "min_participants")
    private int minParticipants;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason")
    private CancelReason cancelReason;

    @ElementCollection
    @CollectionTable(name = "tournament_tag", schema = "tournaments",
        joinColumns = @JoinColumn(name = "tournament_id"))
    @Column(name = "tag_id", nullable = false)
    private Set<UUID> tagIds = new HashSet<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private Long version;
}
