package com.plantarena.tournaments.application;

import com.plantarena.tournaments.api.EntryData;
import com.plantarena.tournaments.api.InvitationData;
import com.plantarena.tournaments.api.TagData;
import com.plantarena.tournaments.api.TournamentData;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.Tag;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntry;

/** Домен → api-DTO (api не зависит от domain, LayerRules). */
final class TournamentAssembler {

    private TournamentAssembler() {
    }

    static TournamentData toData(Tournament tournament) {
        return new TournamentData(tournament.id(), tournament.creatorId(), tournament.name(),
            tournament.description(), tournament.type().name(), tournament.status().name(),
            tournament.algorithm().name(), tournament.registrationDeadline(),
            tournament.roundDuration().toSeconds(), tournament.eliminationFraction(),
            tournament.minParticipants(),
            tournament.cancelReason() == null ? null : tournament.cancelReason().name(),
            tournament.tagIds(), tournament.createdAt());
    }

    static InvitationData toData(Invitation invitation) {
        return new InvitationData(invitation.id(), invitation.tournamentId(),
            invitation.userId(), invitation.status().name(), invitation.invitedAt(),
            invitation.respondedAt(), invitation.submittedPlantId());
    }

    static EntryData toData(TournamentEntry entry) {
        return new EntryData(entry.id(), entry.tournamentId(), entry.userId(), entry.plantId(),
            entry.status().name(), entry.joinedAt());
    }

    static TagData toData(Tag tag) {
        return new TagData(tag.id(), tag.name());
    }
}
