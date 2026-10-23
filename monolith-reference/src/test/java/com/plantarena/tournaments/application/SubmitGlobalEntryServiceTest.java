package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.out.ParticipantLocationsGateway.UserLocation;
import com.plantarena.tournaments.application.port.out.PlantDirectoryGateway.PlantSnapshot;
import com.plantarena.tournaments.application.port.in.SubmitGlobalEntryUseCase;
import com.plantarena.tournaments.application.support.FakeParticipantLocationsGateway;
import com.plantarena.tournaments.application.support.FakePlantDirectoryGateway;
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

/** Подача глобальной заявки (раздел 8, алгоритм 1; раздел 6 — только APPROVED). */
@DisplayName("SubmitGlobalEntryService: очередь глобального турнира")
class SubmitGlobalEntryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final FakePlantDirectoryGateway plants = new FakePlantDirectoryGateway();
    private final FakeParticipantLocationsGateway locations = new FakeParticipantLocationsGateway();
    private final FakePlantEligibilityGateway eligibility = new FakePlantEligibilityGateway();
    private final InMemoryTournamentEntryRepository entries = new InMemoryTournamentEntryRepository();
    private final SubmitGlobalEntryService service = new SubmitGlobalEntryService(
        plants, locations, eligibility, entries, new TournamentsAccessPolicy(),
        Clock.fixed(NOW, ZoneOffset.UTC), txTemplate());

    @Test
    @DisplayName("одобренное растение + координаты → QUEUED, резерв с idempotency key = entryId")
    void подача_в_очередь() {
        UUID user = UUID.randomUUID();
        UUID plantId = UUID.randomUUID();
        plants.plants.put(plantId, new PlantSnapshot(plantId, user, true));
        locations.locations.put(user, new UserLocation(55.7558, 37.6173, 1L));

        SubmitGlobalEntryUseCase.GlobalEntryView view = service.submit(actor(user), plantId);

        assertThat(view.status()).isEqualTo("QUEUED");
        TournamentEntry entry = entries.findById(view.id()).orElseThrow();
        assertThat(entry.status()).isEqualTo(EntryStatus.QUEUED);
        assertThat(entry.tournamentId()).isEqualTo(GlobalCompetitionId.VALUE);
        assertThat(eligibility.reservationsByKey).containsValue(entry.reservationId());
    }

    @Test
    @DisplayName("не одобренное растение — 409 PLANT_NOT_APPROVED (раздел 6)")
    void не_одобренное() {
        UUID user = UUID.randomUUID();
        UUID plantId = UUID.randomUUID();
        plants.plants.put(plantId, new PlantSnapshot(plantId, user, false));
        locations.locations.put(user, new UserLocation(55.7558, 37.6173, 1L));
        assertThatThrownBy(() -> service.submit(actor(user), plantId))
            .isInstanceOf(PlantNotApprovedException.class);
    }

    @Test
    @DisplayName("без координат — 409 LOCATION_REQUIRED (раздел 8)")
    void без_координат() {
        UUID user = UUID.randomUUID();
        UUID plantId = UUID.randomUUID();
        plants.plants.put(plantId, new PlantSnapshot(plantId, user, true));
        assertThatThrownBy(() -> service.submit(actor(user), plantId))
            .isInstanceOf(LocationRequiredException.class);
    }

    @Test
    @DisplayName("второе активное участие — 409 GLOBAL_ENTRY_ACTIVE (допущение 5)")
    void второе_активное() {
        UUID user = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        plants.plants.put(first, new PlantSnapshot(first, user, true));
        plants.plants.put(second, new PlantSnapshot(second, user, true));
        locations.locations.put(user, new UserLocation(55.7558, 37.6173, 1L));
        service.submit(actor(user), first);
        assertThatThrownBy(() -> service.submit(actor(user), second))
            .isInstanceOf(ActiveGlobalEntryExistsException.class);
    }

    @Test
    @DisplayName("чужое/несуществующее растение — 404 (скрыто)")
    void чужое_растение() {
        UUID user = UUID.randomUUID();
        UUID plantId = UUID.randomUUID();
        plants.plants.put(plantId, new PlantSnapshot(plantId, UUID.randomUUID(), true));
        locations.locations.put(user, new UserLocation(55.7558, 37.6173, 1L));
        assertThatThrownBy(() -> service.submit(actor(user), plantId))
            .isInstanceOf(SubmittedPlantNotFoundException.class);
        assertThatThrownBy(() -> service.submit(actor(user), UUID.randomUUID()))
            .isInstanceOf(SubmittedPlantNotFoundException.class);
    }

    private CurrentActor actor(UUID userId) {
        return new CurrentActor(userId, Set.of(AppRole.USER), false);
    }

    /** TransactionTemplate без менеджера транзакций: выполняет действие сразу (unit). */
    private org.springframework.transaction.support.TransactionTemplate txTemplate() {
        return new org.springframework.transaction.support.TransactionTemplate() {
            @Override
            public <T> T execute(
                org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(
                    new org.springframework.transaction.support.SimpleTransactionStatus());
            }
        };
    }
}
