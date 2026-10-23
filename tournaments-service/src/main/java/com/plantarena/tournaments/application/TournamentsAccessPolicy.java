package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.Tournament;
import org.springframework.stereotype.Component;


@Component
public class TournamentsAccessPolicy {

    public void requireIdentified(CurrentActor actor) {
        if (actor == null || actor.isGuest()) {
            throw new NotIdentifiedException(
                "Требуется идентифицированный пользователь (X-Demo-User-Id в dev/test, ADR-005)");
        }
    }

    
    public void requireModeratorOrAdmin(CurrentActor actor) {
        requireIdentified(actor);
        if (!actor.hasRole(AppRole.MODERATOR) && !actor.hasRole(AppRole.ADMIN)) {
            throw new AccessDeniedException("Действие доступно модератору или администратору");
        }
    }

    
    public void requireAdmin(CurrentActor actor) {
        requireIdentified(actor);
        if (!actor.hasRole(AppRole.ADMIN)) {
            throw new AccessDeniedException("Действие доступно администратору");
        }
    }

    
    public void requireOrganizer(CurrentActor actor, Tournament tournament) {
        requireIdentified(actor);
        if (actor.userId().equals(tournament.creatorId()) || actor.hasRole(AppRole.ADMIN)) {
            return;
        }
        throw new AccessDeniedException("Действие доступно организатору турнира");
    }

    
    public void requireAddressee(CurrentActor actor, Invitation invitation) {
        requireIdentified(actor);
        if (actor.userId().equals(invitation.userId())) {
            return;
        }
        throw new InvitationNotFoundException(
            "Приглашение не найдено: " + invitation.id());
    }

    
    public void requireTournamentViewer(CurrentActor actor, Tournament tournament,
                                        boolean visibleBeyondOrganizer) {
        requireIdentified(actor);
        if (actor.userId().equals(tournament.creatorId()) || actor.hasRole(AppRole.ADMIN)
                || visibleBeyondOrganizer) {
            return;
        }
        throw new TournamentNotFoundException("Турнир не найден: " + tournament.id());
    }
}
