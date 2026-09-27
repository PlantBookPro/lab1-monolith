package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.Tournament;
import org.springframework.stereotype.Component;

/**
 * AccessPolicy контекста tournaments (раздел 2/13): правила единого языка.
 * Создание турнира/тегов — модератор/админ; удаление тега — админ;
 * управление турниром — организатор (создатель) или админ; чужое приглашение
 * скрыто (404, как растения plants); просмотр турнира — организатор, админ,
 * активное приглашение (INVITED/ACCEPTED_PENDING_MODERATION/READY) или
 * участие.
 */
@Component
public class TournamentsAccessPolicy {

    public void requireIdentified(CurrentActor actor) {
        if (actor == null || actor.isGuest()) {
            throw new NotIdentifiedException(
                "Требуется идентифицированный пользователь (X-Demo-User-Id в dev/test, ADR-005)");
        }
    }

    /** Создание турнира и тегов: модератор или админ (раздел 13). */
    public void requireModeratorOrAdmin(CurrentActor actor) {
        requireIdentified(actor);
        if (!actor.hasRole(AppRole.MODERATOR) && !actor.hasRole(AppRole.ADMIN)) {
            throw new AccessDeniedException("Действие доступно модератору или администратору");
        }
    }

    /** Изменение/удаление тега: админ (раздел 13). */
    public void requireAdmin(CurrentActor actor) {
        requireIdentified(actor);
        if (!actor.hasRole(AppRole.ADMIN)) {
            throw new AccessDeniedException("Действие доступно администратору");
        }
    }

    /** Управление турниром: создатель или админ (раздел 13). */
    public void requireOrganizer(CurrentActor actor, Tournament tournament) {
        requireIdentified(actor);
        if (actor.userId().equals(tournament.creatorId()) || actor.hasRole(AppRole.ADMIN)) {
            return;
        }
        throw new AccessDeniedException("Действие доступно организатору турнира");
    }

    /** Действие с приглашением: только адресат; чужое скрыто (404). */
    public void requireAddressee(CurrentActor actor, Invitation invitation) {
        requireIdentified(actor);
        if (actor.userId().equals(invitation.userId())) {
            return;
        }
        throw new InvitationNotFoundException(
            "Приглашение не найдено: " + invitation.id());
    }

    /**
     * Просмотр турнира: организатор, админ, активное приглашение или участие.
     * Иначе скрыто (404, раздел 13).
     *
     * @param visibleBeyondOrganizer есть ли у actor активное приглашение/участие
     */
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
