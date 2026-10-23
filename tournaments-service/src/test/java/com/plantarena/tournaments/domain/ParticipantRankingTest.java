package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Tie-break (допущение 8): score DESC, joinedAt ASC, entryId ASC. */
@DisplayName("Итоговый рейтинг окна: детерминированный порядок")
class ParticipantRankingTest {

    @Test
    @DisplayName("score DESC: худший — последний")
    void по_счёту() {
        WindowParticipant best = participant(10L, "2026-09-27T10:00:02Z", entry(7));
        WindowParticipant middle = participant(0L, "2026-09-27T10:00:00Z", entry(8));
        WindowParticipant worst = participant(-1L, "2026-09-27T10:00:01Z", entry(9));
        assertThat(ParticipantRanking.rank(List.of(worst, best, middle)))
            .containsExactly(best, middle, worst);
    }

    @Test
    @DisplayName("равный счёт → joinedAt ASC")
    void равный_счёт_по_времени() {
        WindowParticipant earlier = participant(5L, "2026-09-27T10:00:00Z", entry(7));
        WindowParticipant later = participant(5L, "2026-09-27T10:00:01Z", entry(8));
        assertThat(ParticipantRanking.rank(List.of(later, earlier)))
            .containsExactly(earlier, later);
    }

    @Test
    @DisplayName("равный счёт и время → entryId ASC; без голосов — тот же порядок")
    void полный_тай_брейк() {
        WindowParticipant low = participant(0L, "2026-09-27T10:00:00Z", entry(1));
        WindowParticipant high = participant(0L, "2026-09-27T10:00:00Z", entry(2));
        assertThat(ParticipantRanking.rank(List.of(high, low))).containsExactly(low, high);
    }

    private UUID entry(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-00000000000" + suffix);
    }

    private WindowParticipant participant(long score, String joinedAt, UUID entryId) {
        WindowParticipant participant = WindowParticipant.restore(UUID.randomUUID(), entryId,
            UUID.randomUUID(), score, ParticipantResult.ACTIVE, Instant.parse(joinedAt));
        return participant;
    }
}
