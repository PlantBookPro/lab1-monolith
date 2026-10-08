package com.plantarena.tournaments.adapter.out.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


@Entity
@Table(name = "window_participant", schema = "tournaments")
public class WindowParticipantJpaEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "window_id")
    private VotingWindowJpaEntity window;

    @Column(name = "entry_id", nullable = false)
    private UUID entryId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private long score;

    @Column(nullable = false)
    private String result;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @OneToMany(mappedBy = "participant", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<VoteJpaEntity> votes = new ArrayList<>();

    UUID getId() {
        return id;
    }

    UUID getEntryId() {
        return entryId;
    }

    UUID getUserId() {
        return userId;
    }

    long getScore() {
        return score;
    }

    String getResult() {
        return result;
    }

    Instant getJoinedAt() {
        return joinedAt;
    }

    List<VoteJpaEntity> getVotes() {
        return votes;
    }

    void setId(UUID id) {
        this.id = id;
    }

    void setWindow(VotingWindowJpaEntity window) {
        this.window = window;
    }

    void setEntryId(UUID entryId) {
        this.entryId = entryId;
    }

    void setUserId(UUID userId) {
        this.userId = userId;
    }

    void setScore(long score) {
        this.score = score;
    }

    void setResult(String result) {
        this.result = result;
    }

    void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }
}
