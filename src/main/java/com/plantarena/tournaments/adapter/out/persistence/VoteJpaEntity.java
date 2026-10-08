package com.plantarena.tournaments.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;


@Entity
@Table(name = "vote", schema = "tournaments")
public class VoteJpaEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "window_participant_id")
    private WindowParticipantJpaEntity participant;

    @Column(name = "subject_key", nullable = false)
    private String subjectKey;

    @Column(nullable = false)
    private String value;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    UUID getId() {
        return id;
    }

    String getSubjectKey() {
        return subjectKey;
    }

    String getValue() {
        return value;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void setId(UUID id) {
        this.id = id;
    }

    void setParticipant(WindowParticipantJpaEntity participant) {
        this.participant = participant;
    }

    void setSubjectKey(String subjectKey) {
        this.subjectKey = subjectKey;
    }

    void setValue(String value) {
        this.value = value;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
