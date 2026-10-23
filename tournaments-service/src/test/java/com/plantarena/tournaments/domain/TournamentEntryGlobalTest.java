package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Глобальные переходы участия (раздел 11): QUEUED → … → ELIMINATED, WITHDRAWN. */
@DisplayName("TournamentEntry: глобальный жизненный цикл")
class TournamentEntryGlobalTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @Test
    @DisplayName("полный путь: QUEUED → QUALIFYING → FINAL_PENDING → FINALIST → ELIMINATED")
    void путь_финалиста_до_поражения() {
        TournamentEntry entry = TournamentEntry.queueForGlobal(UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW);
        assertThat(entry.status()).isEqualTo(EntryStatus.QUEUED);
        entry.startQualifying();
        assertThat(entry.status()).isEqualTo(EntryStatus.QUALIFYING);
        entry.promoteToFinalPending();
        assertThat(entry.status()).isEqualTo(EntryStatus.FINAL_PENDING);
        entry.becomeFinalist();
        assertThat(entry.status()).isEqualTo(EntryStatus.FINALIST);
        entry.eliminateFromGlobal();
        assertThat(entry.status()).isEqualTo(EntryStatus.ELIMINATED);
    }

    @Test
    @DisplayName("QUALIFYING → ELIMINATED допустим (поражение в квалификации)")
    void поражение_в_квалификации() {
        TournamentEntry entry = queued();
        entry.startQualifying();
        entry.eliminateFromGlobal();
        assertThat(entry.status()).isEqualTo(EntryStatus.ELIMINATED);
    }

    @Test
    @DisplayName("WITHDRAWN только из QUEUED; снятое не участвует")
    void снятие_только_из_очереди() {
        TournamentEntry entry = queued();
        entry.withdraw();
        assertThat(entry.status()).isEqualTo(EntryStatus.WITHDRAWN);
        assertThatThrownBy(entry::startQualifying)
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(entry::withdraw)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("недопустимые переходы — ошибка состояния (QUEUED нельзя в финал)")
    void недопустимые_переходы() {
        TournamentEntry queued = queued();
        assertThatThrownBy(queued::promoteToFinalPending)
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(queued::becomeFinalist)
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(queued::eliminateFromGlobal)
            .isInstanceOf(IllegalStateException.class);
        TournamentEntry finalist = finalist();
        assertThatThrownBy(finalist::startQualifying)
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(finalist::promoteToFinalPending)
            .isInstanceOf(IllegalStateException.class);
    }

    private TournamentEntry queued() {
        return TournamentEntry.queueForGlobal(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), NOW);
    }

    private TournamentEntry finalist() {
        TournamentEntry entry = queued();
        entry.startQualifying();
        entry.promoteToFinalPending();
        entry.becomeFinalist();
        return entry;
    }
}
