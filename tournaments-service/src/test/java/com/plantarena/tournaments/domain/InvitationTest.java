package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Агрегат Invitation: переходы заявок раздела 7, история последней подачи")
class InvitationTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final UUID TOURNAMENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORGANIZER_ID = UUID.randomUUID();

    @Test
    @DisplayName("фабрика: INVITED, invitedAt=now, без заявки")
    void фабрика_invited() {
        Invitation invitation = Invitation.invite(TOURNAMENT_ID, USER_ID, ORGANIZER_ID, NOW);

        assertThat(invitation.status()).isEqualTo(InvitationStatus.INVITED);
        assertThat(invitation.tournamentId()).isEqualTo(TOURNAMENT_ID);
        assertThat(invitation.userId()).isEqualTo(USER_ID);
        assertThat(invitation.invitedBy()).isEqualTo(ORGANIZER_ID);
        assertThat(invitation.invitedAt()).isEqualTo(NOW);
        assertThat(invitation.respondedAt()).isNull();
        assertThat(invitation.submittedPlantId()).isNull();
        assertThat(invitation.reservationId()).isNull();
    }

    @Test
    @DisplayName("accept: PENDING-растение → ACCEPTED_PENDING_MODERATION; APPROVED → сразу READY")
    void accept_два_пути() {
        UUID plantId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        Invitation pending = Invitation.invite(TOURNAMENT_ID, USER_ID, ORGANIZER_ID, NOW);
        pending.accept(plantId, reservationId, key, false, NOW.plusSeconds(1));
        assertThat(pending.status()).isEqualTo(InvitationStatus.ACCEPTED_PENDING_MODERATION);
        assertThat(pending.submittedPlantId()).isEqualTo(plantId);
        assertThat(pending.reservationId()).isEqualTo(reservationId);
        assertThat(pending.submissionKey()).isEqualTo(key);
        assertThat(pending.respondedAt()).isEqualTo(NOW.plusSeconds(1));

        Invitation ready = Invitation.invite(TOURNAMENT_ID, USER_ID, ORGANIZER_ID, NOW);
        ready.accept(plantId, reservationId, key, true, NOW.plusSeconds(1));
        assertThat(ready.status()).isEqualTo(InvitationStatus.READY);
    }

    @Test
    @DisplayName("accept только из INVITED; закрытое приглашение не принимается")
    void accept_только_из_invited() {
        Invitation accepted = accepted(false);
        assertThatThrownBy(() -> accepted.accept(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), false, NOW))
            .isInstanceOf(IllegalStateException.class);

        Invitation declined = Invitation.invite(TOURNAMENT_ID, USER_ID, ORGANIZER_ID, NOW);
        declined.decline(NOW);
        assertThatThrownBy(() -> declined.accept(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), false, NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markReady: ACCEPTED_PENDING_MODERATION → READY (решение модерации)")
    void mark_ready() {
        Invitation invitation = accepted(false);
        invitation.markReady(NOW.plusSeconds(5));
        assertThat(invitation.status()).isEqualTo(InvitationStatus.READY);
        assertThat(invitation.respondedAt()).isEqualTo(NOW.plusSeconds(5));

        assertThatThrownBy(() -> Invitation.invite(TOURNAMENT_ID, USER_ID, ORGANIZER_ID, NOW)
            .markReady(NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("rollbackToInvited: REJECTED → INVITED, история подачи сохранена, резерв сброшен")
    void rollback_после_rejected() {
        Invitation invitation = accepted(false);
        UUID plantId = invitation.submittedPlantId();

        invitation.rollbackToInvited(NOW.plusSeconds(5));

        assertThat(invitation.status()).isEqualTo(InvitationStatus.INVITED);
        assertThat(invitation.submittedPlantId()).isEqualTo(plantId); // история последней подачи
        assertThat(invitation.reservationId()).isNull();
        assertThat(invitation.submissionKey()).isNull(); // новая подача — новый ключ
    }

    @Test
    @DisplayName("decline: из INVITED и ACCEPTED_PENDING_MODERATION; повтор — ошибка")
    void decline_переходы() {
        Invitation fromInvited = Invitation.invite(TOURNAMENT_ID, USER_ID, ORGANIZER_ID, NOW);
        fromInvited.decline(NOW);
        assertThat(fromInvited.status()).isEqualTo(InvitationStatus.DECLINED);

        Invitation fromAccepted = accepted(false);
        fromAccepted.decline(NOW);
        assertThat(fromAccepted.status()).isEqualTo(InvitationStatus.DECLINED);

        assertThatThrownBy(() -> fromAccepted.decline(NOW.plusSeconds(1)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("decline: из READY — отказ до старта, история подачи сохранена (раздел 7)")
    void decline_из_ready() {
        Invitation ready = accepted(true);
        UUID plantId = ready.submittedPlantId();

        ready.decline(NOW.plusSeconds(5));

        assertThat(ready.status()).isEqualTo(InvitationStatus.DECLINED);
        assertThat(ready.submittedPlantId()).isEqualTo(plantId); // история последней подачи
        assertThat(ready.reservationId()).isNull();
        assertThat(ready.submissionKey()).isNull();
    }

    @Test
    @DisplayName("revoke: только не принятые (INVITED); принятое — конфликт организатора")
    void revoke_только_invited() {
        Invitation invitation = Invitation.invite(TOURNAMENT_ID, USER_ID, ORGANIZER_ID, NOW);
        invitation.revoke(NOW);
        assertThat(invitation.status()).isEqualTo(InvitationStatus.REVOKED);

        assertThatThrownBy(() -> accepted(false).revoke(NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("expire: INVITED и ACCEPTED_PENDING_MODERATION → EXPIRED (старт по дедлайну)")
    void expire_переходы() {
        Invitation invited = Invitation.invite(TOURNAMENT_ID, USER_ID, ORGANIZER_ID, NOW);
        invited.expire(NOW.plusSeconds(10));
        assertThat(invited.status()).isEqualTo(InvitationStatus.EXPIRED);

        Invitation accepted = accepted(false);
        accepted.expire(NOW.plusSeconds(10));
        assertThat(accepted.status()).isEqualTo(InvitationStatus.EXPIRED);
        assertThat(accepted.reservationId()).isNull();

        assertThatThrownBy(() -> accepted(true).expire(NOW.plusSeconds(10)))
            .isInstanceOf(IllegalStateException.class); // READY не истекает
    }

    private Invitation accepted(boolean approved) {
        Invitation invitation = Invitation.invite(TOURNAMENT_ID, USER_ID, ORGANIZER_ID, NOW);
        invitation.accept(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            approved, NOW.plusSeconds(1));
        return invitation;
    }
}
