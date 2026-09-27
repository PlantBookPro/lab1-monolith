package com.plantarena.tournaments.application;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.CreateTournamentUseCase;
import com.plantarena.tournaments.application.support.FakePlantEligibilityGateway;
import com.plantarena.tournaments.application.support.InMemoryInvitationRepository;
import com.plantarena.tournaments.application.support.InMemoryTagRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Реакция на PlantModerationDecided: READY / возврат в INVITED (раздел 7)")
class PlantModerationReactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryInvitationRepository invitations =
        new InMemoryInvitationRepository(tournaments);
    private final FakePlantEligibilityGateway eligibility = new FakePlantEligibilityGateway();
    private final TournamentsAccessPolicy accessPolicy = new TournamentsAccessPolicy();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TournamentAdministrationService administration =
        new TournamentAdministrationService(tournaments, new InMemoryTagRepository(),
            invitations, eligibility, accessPolicy, clock);
    private final PlantModerationReactionService service =
        new PlantModerationReactionService(tournaments, invitations, eligibility, clock);

    private final UUID organizerId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private Invitation acceptedPending(Instant registrationDeadline) {
        CurrentActor organizerActor = CurrentActor.identified(organizerId,
            Set.of(com.plantarena.shared.security.AppRole.USER,
                com.plantarena.shared.security.AppRole.MODERATOR));
        UUID tournamentId = administration.create(organizerActor,
            new CreateTournamentUseCase.CreateTournamentCommand("Турнир", null,
                registrationDeadline, Duration.ofHours(1), 0.5, 2, Set.of())).id();
        administration.openRegistration(organizerActor, tournamentId);
        Invitation invitation = invitations.save(Invitation.invite(tournamentId, userId,
            organizerId, NOW));
        invitation.accept(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false, NOW);
        return invitations.save(invitation);
    }

    @Test
    @DisplayName("APPROVED до дедлайна: резерв подтверждён, заявка READY")
    void approved_до_дедлайна() {
        Invitation invitation = acceptedPending(NOW.plusSeconds(3600));

        service.onPlantModerationDecided(invitation.submittedPlantId(), "APPROVED");

        assertThat(invitations.findById(invitation.id()).orElseThrow().status())
            .isEqualTo(InvitationStatus.READY);
        assertThat(eligibility.confirmed).hasSize(1);
    }

    @Test
    @DisplayName("REJECTED: возврат в INVITED, резерв освобождён, история подачи сохранена")
    void rejected_возврат() {
        Invitation invitation = acceptedPending(NOW.plusSeconds(3600));
        UUID reservationId = invitation.reservationId();
        UUID plantId = invitation.submittedPlantId();

        service.onPlantModerationDecided(plantId, "REJECTED");

        Invitation after = invitations.findById(invitation.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(InvitationStatus.INVITED);
        assertThat(after.submittedPlantId()).isEqualTo(plantId); // история последней подачи
        assertThat(eligibility.isReleased(reservationId)).isTrue();
    }

    @Test
    @DisplayName("APPROVED после дедлайна: заявка не меняется (терминальные правила раздела 7)")
    void approved_после_дедлайна() {
        Invitation invitation = acceptedPending(NOW.plusSeconds(10));
        PlantModerationReactionService lateService =
            new PlantModerationReactionService(tournaments, invitations, eligibility,
                Clock.fixed(NOW.plusSeconds(20), ZoneOffset.UTC));

        lateService.onPlantModerationDecided(invitation.submittedPlantId(), "APPROVED");

        assertThat(invitations.findById(invitation.id()).orElseThrow().status())
            .isEqualTo(InvitationStatus.ACCEPTED_PENDING_MODERATION);
    }

    @Test
    @DisplayName("решение по растению без заявки — no-op")
    void без_заявки() {
        service.onPlantModerationDecided(UUID.randomUUID(), "APPROVED");
        assertThat(eligibility.confirmed).isEmpty();
        assertThat(eligibility.released).isEmpty();
    }
}
