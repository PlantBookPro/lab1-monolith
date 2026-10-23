package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.webflux.ReactiveCurrentActorProvider;
import com.plantarena.tournaments.application.port.in.CancelTournamentUseCase;
import com.plantarena.tournaments.application.port.in.CreateTournamentUseCase;
import com.plantarena.tournaments.application.port.in.DeleteTournamentUseCase;
import com.plantarena.tournaments.application.port.in.GetLeaderboardUseCase;
import com.plantarena.tournaments.application.port.in.GetTournamentUseCase;
import com.plantarena.tournaments.application.port.in.InviteUserUseCase;
import com.plantarena.tournaments.application.port.in.ListEntriesUseCase;
import com.plantarena.tournaments.application.port.in.ListInvitationsUseCase;
import com.plantarena.tournaments.application.port.in.ListResultsUseCase;
import com.plantarena.tournaments.application.port.in.ListRoundsUseCase;
import com.plantarena.tournaments.application.port.in.ListTournamentsUseCase;
import com.plantarena.tournaments.application.port.in.OpenRegistrationUseCase;
import com.plantarena.tournaments.application.port.in.RevokeInvitationUseCase;
import com.plantarena.tournaments.application.port.in.StartTournamentUseCase;
import com.plantarena.tournaments.application.port.in.TagsUseCase;
import com.plantarena.tournaments.application.port.in.UpdateTournamentUseCase;
import com.plantarena.tournaments.application.port.in.VotingUseCase;
import com.plantarena.tournaments.application.port.in.WithdrawGlobalEntryUseCase;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;

@DisplayName("DELETE-эндпоинты: Mono излучает ResponseEntity 204 (thenReturn, а не map на пустом Mono)")
class DeleteEndpointsNoContentTest {

    private final CurrentActor admin =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER, AppRole.ADMIN));
    private final ReactiveCurrentActorProvider actors = mock(ReactiveCurrentActorProvider.class);

    {
        org.mockito.Mockito.when(actors.currentActor()).thenReturn(Mono.just(admin));
    }

    @Test
    void tournamentDeleteEmits204() {
        var deleteTournament = mock(DeleteTournamentUseCase.class);
        var controller = new TournamentController(
            mock(CreateTournamentUseCase.class), mock(UpdateTournamentUseCase.class),
            deleteTournament, mock(OpenRegistrationUseCase.class),
            mock(CancelTournamentUseCase.class), mock(StartTournamentUseCase.class),
            mock(ListTournamentsUseCase.class), mock(GetTournamentUseCase.class),
            mock(ListEntriesUseCase.class), mock(InviteUserUseCase.class),
            mock(RevokeInvitationUseCase.class), mock(ListInvitationsUseCase.class),
            mock(ListRoundsUseCase.class), mock(GetLeaderboardUseCase.class),
            mock(ListResultsUseCase.class), actors);

        var response = controller.delete(UUID.randomUUID()).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        org.mockito.Mockito.verify(deleteTournament).delete(eq(admin), any(UUID.class));
    }

    @Test
    void revokeInvitationEmits204() {
        var revokeInvitation = mock(RevokeInvitationUseCase.class);
        var controller = new TournamentController(
            mock(CreateTournamentUseCase.class), mock(UpdateTournamentUseCase.class),
            mock(DeleteTournamentUseCase.class), mock(OpenRegistrationUseCase.class),
            mock(CancelTournamentUseCase.class), mock(StartTournamentUseCase.class),
            mock(ListTournamentsUseCase.class), mock(GetTournamentUseCase.class),
            mock(ListEntriesUseCase.class), mock(InviteUserUseCase.class),
            revokeInvitation, mock(ListInvitationsUseCase.class),
            mock(ListRoundsUseCase.class), mock(GetLeaderboardUseCase.class),
            mock(ListResultsUseCase.class), actors);

        var response = controller.revokeInvitation(UUID.randomUUID(), UUID.randomUUID()).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        org.mockito.Mockito.verify(revokeInvitation)
            .revoke(eq(admin), any(UUID.class), any(UUID.class));
    }

    @Test
    void tagDeleteEmits204() {
        var tags = mock(TagsUseCase.class);
        var controller = new TagController(tags, actors);

        var response = controller.delete(UUID.randomUUID()).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        org.mockito.Mockito.verify(tags).delete(eq(admin), any(UUID.class));
    }

    @Test
    void globalWithdrawEmits204() {
        var withdrawEntry = mock(WithdrawGlobalEntryUseCase.class);
        var controller = new GlobalController(
            mock(com.plantarena.tournaments.application.port.in.GetGlobalInfoUseCase.class),
            mock(com.plantarena.tournaments.application.port.in.ListGlobalClustersUseCase.class),
            mock(com.plantarena.tournaments.application.port.in.GetGlobalLeaderboardUseCase.class),
            mock(com.plantarena.tournaments.application.port.in.SubmitGlobalEntryUseCase.class),
            withdrawEntry,
            mock(com.plantarena.tournaments.application.port.in.GetMyGlobalEntryUseCase.class),
            actors);

        var response = controller.withdraw(UUID.randomUUID()).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        org.mockito.Mockito.verify(withdrawEntry).withdraw(eq(admin), any(UUID.class));
    }

    @Test
    void votingRemoveEmits204() {
        var voting = mock(VotingUseCase.class);
        org.mockito.Mockito.when(voting.remove(any(), isNull(), any(UUID.class), any(UUID.class)))
            .thenReturn(0L);
        var controller = new VotingController(voting, actors);

        var response = controller.remove(UUID.randomUUID(), UUID.randomUUID(), null).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        org.mockito.Mockito.verify(voting)
            .remove(eq(admin), isNull(), any(UUID.class), any(UUID.class));
    }
}
