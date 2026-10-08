package com.plantarena.tournaments.application;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.SubmitGlobalEntryUseCase;
import com.plantarena.tournaments.application.port.out.ParticipantLocationsGateway;
import com.plantarena.tournaments.application.port.out.PlantDirectoryGateway;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;


@Service
public class SubmitGlobalEntryService implements SubmitGlobalEntryUseCase {

    private final PlantDirectoryGateway plants;
    private final ParticipantLocationsGateway locations;
    private final PlantEligibilityGateway eligibility;
    private final TournamentEntryRepository entries;
    private final TournamentsAccessPolicy accessPolicy;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public SubmitGlobalEntryService(PlantDirectoryGateway plants,
                                    ParticipantLocationsGateway locations,
                                    PlantEligibilityGateway eligibility,
                                    TournamentEntryRepository entries,
                                    TournamentsAccessPolicy accessPolicy, Clock clock,
                                    TransactionTemplate transactionTemplate) {
        this.plants = plants;
        this.locations = locations;
        this.eligibility = eligibility;
        this.entries = entries;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public GlobalEntryView submit(CurrentActor actor, UUID plantId) {
        accessPolicy.requireIdentified(actor);
        locations.findLocation(actor.userId())
            .orElseThrow(() -> new LocationRequiredException(
                "Для глобального участия нужны координаты в профиле (PUT /me/location)"));
        PlantDirectoryGateway.PlantSnapshot plant = plants.findById(plantId)
            .filter(snapshot -> snapshot.ownerId().equals(actor.userId()))
            .orElseThrow(() -> new SubmittedPlantNotFoundException(
                "Растение не найдено: " + plantId));
        if (!plant.approved()) {
            throw new PlantNotApprovedException(
                "Глобальный турнир принимает только одобренные модерацией растения (раздел 6)");
        }
        if (entries.findActiveGlobalByUserId(GlobalCompetitionId.VALUE, actor.userId())
            .isPresent()) {
            throw new ActiveGlobalEntryExistsException(
                "У пользователя уже есть активное глобальное участие (допущение 5)");
        }
        return transactionTemplate.execute(status -> {
            UUID idempotencyKey = UUID.randomUUID();
            UUID reservationId = eligibility.reserve(actor.userId(), plantId, idempotencyKey);
            TournamentEntry entry = TournamentEntry.queueForGlobal(GlobalCompetitionId.VALUE,
                actor.userId(), plantId, reservationId, clock.instant());
            entries.save(entry);
            return new GlobalEntryView(entry.id(), entry.plantId(), entry.status().name(),
                entry.joinedAt());
        });
    }
}
