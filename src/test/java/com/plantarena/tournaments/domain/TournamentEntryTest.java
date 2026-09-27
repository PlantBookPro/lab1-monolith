package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Агрегат TournamentEntry: допуск участника при старте (ACTIVE)")
class TournamentEntryTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @Test
    @DisplayName("admit: ACTIVE с зафиксированным растением и резервом")
    void admit_active() {
        UUID tournamentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID plantId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        TournamentEntry entry = TournamentEntry.admit(tournamentId, userId, plantId,
            reservationId, NOW);

        assertThat(entry.tournamentId()).isEqualTo(tournamentId);
        assertThat(entry.userId()).isEqualTo(userId);
        assertThat(entry.plantId()).isEqualTo(plantId);
        assertThat(entry.reservationId()).isEqualTo(reservationId);
        assertThat(entry.status()).isEqualTo(EntryStatus.ACTIVE);
        assertThat(entry.joinedAt()).isEqualTo(NOW);
    }
}
