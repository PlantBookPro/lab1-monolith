package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.support.FakePlantEligibilityGateway;
import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.domain.EntryStatus;
import com.plantarena.tournaments.domain.TournamentEntry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Снятие заявки из очереди (раздел 13): только владелец и только QUEUED. */
@DisplayName("WithdrawGlobalEntryService: снятие из очереди")
class WithdrawGlobalEntryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final FakePlantEligibilityGateway eligibility = new FakePlantEligibilityGateway();
    private final InMemoryTournamentEntryRepository entries = new InMemoryTournamentEntryRepository();
    private final WithdrawGlobalEntryService service = new WithdrawGlobalEntryService(
        entries, eligibility, new TournamentsAccessPolicy(), txTemplate());

    @Test
    @DisplayName("QUEUED → WITHDRAWN, резерв освобождается")
    void снятие() {
        TournamentEntry entry = queued();
        entries.save(entry);
        service.withdraw(actor(entry.userId()), entry.id());
        assertThat(entries.findById(entry.id()).orElseThrow().status())
            .isEqualTo(EntryStatus.WITHDRAWN);
        assertThat(eligibility.released).contains(entry.reservationId());
    }

    @Test
    @DisplayName("не из очереди — 409 ENTRY_IN_WINDOW")
    void не_из_очереди() {
        TournamentEntry entry = queued();
        entry.startQualifying();
        entries.save(entry);
        assertThatThrownBy(() -> service.withdraw(actor(entry.userId()), entry.id()))
            .isInstanceOf(GlobalEntryNotWithdrawableException.class);
    }

    @Test
    @DisplayName("чужая заявка скрыта — 404")
    void чужая_заявка() {
        TournamentEntry entry = queued();
        entries.save(entry);
        assertThatThrownBy(() -> service.withdraw(actor(UUID.randomUUID()), entry.id()))
            .isInstanceOf(GlobalEntryNotFoundException.class);
    }

    private TournamentEntry queued() {
        return TournamentEntry.queueForGlobal(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), NOW);
    }

    private CurrentActor actor(UUID userId) {
        return new CurrentActor(userId, Set.of(AppRole.USER), false);
    }

    /** TransactionTemplate без менеджера транзакций: выполняет действие сразу (unit). */
    private org.springframework.transaction.support.TransactionTemplate txTemplate() {
        return new org.springframework.transaction.support.TransactionTemplate() {
            @Override
            public void executeWithoutResult(java.util.function.Consumer<
                    org.springframework.transaction.TransactionStatus> action) {
                action.accept(new org.springframework.transaction.support.SimpleTransactionStatus());
            }
        };
    }
}
