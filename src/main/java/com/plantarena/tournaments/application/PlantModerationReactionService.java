package com.plantarena.tournaments.application;

import com.plantarena.tournaments.application.port.in.OnPlantModerationDecidedUseCase;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class PlantModerationReactionService implements OnPlantModerationDecidedUseCase {

    private final TournamentRepository tournaments;
    private final InvitationRepository invitations;
    private final PlantEligibilityGateway eligibility;
    private final Clock clock;

    public PlantModerationReactionService(TournamentRepository tournaments,
                                          InvitationRepository invitations,
                                          PlantEligibilityGateway eligibility, Clock clock) {
        this.tournaments = tournaments;
        this.invitations = invitations;
        this.eligibility = eligibility;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void onPlantModerationDecided(UUID plantId, String decision) {
        invitations.findBySubmittedPlantIdAndStatus(plantId,
                InvitationStatus.ACCEPTED_PENDING_MODERATION)
            .ifPresent(invitation -> apply(invitation, plantId, decision));
    }

    private void apply(Invitation invitation, UUID plantId, String decision) {
        Tournament tournament = tournaments.findById(invitation.tournamentId())
            .orElseThrow(() -> new TournamentNotFoundException(
                "Турнир не найден: " + invitation.tournamentId()));
        if ("REJECTED".equals(decision)) {
            if (invitation.reservationId() != null) {
                eligibility.release(invitation.reservationId());
            }
            invitation.rollbackToInvited(clock.instant());
            invitations.save(invitation);
            return;
        }
        if ("APPROVED".equals(decision) && tournament.isAcceptingNow(clock.instant())) {
            
            eligibility.confirm(invitation.userId(), plantId, invitation.reservationId());
            invitation.markReady(clock.instant());
            invitations.save(invitation);
        }
        
    }
}
