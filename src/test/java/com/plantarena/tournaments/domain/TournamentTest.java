package com.plantarena.tournaments.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Агрегат Tournament: state machine DRAFT → REGISTRATION_OPEN → RUNNING → FINISHED, отмена до RUNNING")
class TournamentTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(3600);
    private static final Instant NOW_AFTER_DEADLINE = DEADLINE.plusSeconds(1);

    private Tournament draft() {
        return Tournament.createDraft(UUID.randomUUID(), "Осенний чемпионат", "Описание",
            DEADLINE, Duration.ofHours(1), 0.5, 2, Set.of(), NOW);
    }

    @Test
    @DisplayName("фабрика: DRAFT, PRIVATE, ROUND_ELIMINATION, параметры сохранены")
    void фабрика_draft() {
        UUID creatorId = UUID.randomUUID();
        UUID tagId = UUID.randomUUID();
        Tournament tournament = Tournament.createDraft(creatorId, "  Название  ", "Описание",
            DEADLINE, Duration.ofHours(1), 0.25, 3, Set.of(tagId), NOW);

        assertThat(tournament.status()).isEqualTo(TournamentStatus.DRAFT);
        assertThat(tournament.type()).isEqualTo(TournamentType.PRIVATE);
        assertThat(tournament.algorithm()).isEqualTo(EliminationAlgorithmKind.ROUND_ELIMINATION);
        assertThat(tournament.name()).isEqualTo("Название"); // trim
        assertThat(tournament.creatorId()).isEqualTo(creatorId);
        assertThat(tournament.tagIds()).containsExactly(tagId);
        assertThat(tournament.cancelReason()).isNull();
        assertThat(tournament.createdAt()).isEqualTo(NOW);
        assertThat(tournament.version()).isZero();
    }

    @Test
    @DisplayName("параметры: дедлайн в прошлом, доля 0/1, minParticipants < 2, пустое имя — запрещены")
    void параметры_инварианты() {
        UUID creatorId = UUID.randomUUID();
        assertThatThrownBy(() -> Tournament.createDraft(creatorId, "Н", null,
            NOW.minusSeconds(1), Duration.ofHours(1), 0.5, 2, Set.of(), NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tournament.createDraft(creatorId, "Н", null,
            DEADLINE, Duration.ofHours(1), 0d, 2, Set.of(), NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tournament.createDraft(creatorId, "Н", null,
            DEADLINE, Duration.ofHours(1), 1d, 2, Set.of(), NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tournament.createDraft(creatorId, "Н", null,
            DEADLINE, Duration.ofHours(1), 0.5, 1, Set.of(), NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tournament.createDraft(creatorId, " ", null,
            DEADLINE, Duration.ofHours(1), 0.5, 2, Set.of(), NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tournament.createDraft(creatorId, "Н", null,
            DEADLINE, Duration.ZERO, 0.5, 2, Set.of(), NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("openRegistration: DRAFT → REGISTRATION_OPEN; после дедлайна запрещено")
    void open_registration() {
        Tournament tournament = draft();
        tournament.openRegistration(NOW);
        assertThat(tournament.status()).isEqualTo(TournamentStatus.REGISTRATION_OPEN);

        assertThatThrownBy(() -> draft().openRegistration(DEADLINE))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tournament.openRegistration(NOW))
            .isInstanceOf(IllegalStateException.class); // уже открыта
    }

    @Test
    @DisplayName("start: REGISTRATION_OPEN + now ≥ deadline + READY ≥ minParticipants → RUNNING")
    void start_переход() {
        Tournament tournament = draft();
        assertThatThrownBy(() -> tournament.start(DEADLINE, 2))
            .isInstanceOf(IllegalStateException.class); // ещё DRAFT

        tournament.openRegistration(NOW);
        assertThatThrownBy(() -> tournament.start(NOW, 2))
            .isInstanceOf(IllegalStateException.class); // до дедлайна
        assertThatThrownBy(() -> tournament.start(DEADLINE, 1))
            .isInstanceOf(IllegalStateException.class); // меньше участников

        tournament.start(DEADLINE, 2);
        assertThat(tournament.status()).isEqualTo(TournamentStatus.RUNNING);
        assertThatThrownBy(() -> tournament.start(DEADLINE.plusSeconds(1), 2))
            .isInstanceOf(IllegalStateException.class); // RUNNING не перезапускается
    }

    @Test
    @DisplayName("cancel: DRAFT и REGISTRATION_OPEN → CANCELLED; RUNNING/FINISHED запрещены")
    void cancel_переходы() {
        draft().cancel(NOW); // из DRAFT можно

        Tournament open = draft();
        open.openRegistration(NOW);
        open.cancel(NOW.plusSeconds(1));
        assertThat(open.status()).isEqualTo(TournamentStatus.CANCELLED);
        assertThatThrownBy(() -> open.cancel(NOW.plusSeconds(2)))
            .isInstanceOf(IllegalStateException.class); // уже отменён

        Tournament running = started();
        assertThatThrownBy(() -> running.cancel(DEADLINE.plusSeconds(1)))
            .isInstanceOf(IllegalStateException.class); // активный не отменяется (раздел 7)
    }

    @Test
    @DisplayName("cancelForInsufficientParticipants: REGISTRATION_OPEN → CANCELLED с причиной")
    void cancel_insufficient() {
        Tournament tournament = draft();
        assertThatThrownBy(() -> tournament.cancelForInsufficientParticipants(NOW))
            .isInstanceOf(IllegalStateException.class); // ещё DRAFT

        tournament.openRegistration(NOW);
        tournament.cancelForInsufficientParticipants(DEADLINE);
        assertThat(tournament.status()).isEqualTo(TournamentStatus.CANCELLED);
        assertThat(tournament.cancelReason()).isEqualTo(CancelReason.INSUFFICIENT_PARTICIPANTS);
    }

    @Test
    @DisplayName("updateParameters/replaceTags: только DRAFT; описание — безопасное и после открытия")
    void изменения_параметров() {
        Tournament tournament = draft();
        tournament.updateParameters("Новое имя", DEADLINE.plusSeconds(60),
            Duration.ofHours(2), 0.75, 4, NOW);
        assertThat(tournament.name()).isEqualTo("Новое имя");
        assertThat(tournament.minParticipants()).isEqualTo(4);
        tournament.replaceTags(Set.of(UUID.randomUUID()));
        assertThat(tournament.tagIds()).hasSize(1);

        Tournament open = draft();
        open.openRegistration(NOW);
        assertThatThrownBy(() -> open.updateParameters("Н", DEADLINE,
            Duration.ofHours(1), 0.5, 2, NOW))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> open.replaceTags(Set.of()))
            .isInstanceOf(IllegalStateException.class);

        open.updateDescription("Безопасное описание"); // разрешено после открытия
        assertThat(open.description()).isEqualTo("Безопасное описание");
        Tournament running = started();
        running.updateDescription("Описание бегущего"); // и в RUNNING
        assertThatThrownBy(() -> running.updateParameters("Н", DEADLINE,
            Duration.ofHours(1), 0.5, 2, DEADLINE))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("canInvite/isAcceptingNow: дедлайн и статус решают")
    void окна_приглашений() {
        Tournament tournament = draft();
        assertThat(tournament.canInvite(NOW)).isTrue();   // DRAFT, до дедлайна
        assertThat(tournament.isAcceptingNow(NOW)).isFalse(); // приём закрыт

        tournament.openRegistration(NOW);
        assertThat(tournament.isAcceptingNow(NOW)).isTrue();
        assertThat(tournament.isAcceptingNow(DEADLINE)).isFalse(); // [.., deadline)

        tournament.start(DEADLINE, 2);
        assertThat(tournament.canInvite(DEADLINE.plusSeconds(1))).isFalse();
        assertThat(tournament.isAcceptingNow(DEADLINE.plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("finish: RUNNING → FINISHED (победитель определён, раздел 7)")
    void finish_из_running() {
        Tournament tournament = started(); // createDraft → openRegistration → start
        tournament.finish(NOW_AFTER_DEADLINE);
        assertThat(tournament.status()).isEqualTo(TournamentStatus.FINISHED);
    }

    @Test
    @DisplayName("finish из не-RUNNING — ошибка состояния")
    void finish_не_из_running() {
        Tournament draft = Tournament.createDraft(UUID.randomUUID(), "Черновик", null,
            DEADLINE.plusSeconds(600), Duration.ofSeconds(3600), 0.5, 2, Set.of(), NOW);
        assertThatThrownBy(() -> draft.finish(NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    private Tournament started() {
        Tournament tournament = draft();
        tournament.openRegistration(NOW);
        tournament.start(DEADLINE, 2);
        return tournament;
    }
}
