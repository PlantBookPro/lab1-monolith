package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Глобальные окна (раздел 8, алгоритмы 3–8): квалификация и финал. */
@DisplayName("VotingWindow: scope QUALIFICATION/FINAL")
class VotingWindowGlobalTest {

    private static final UUID TOURNAMENT = UUID.randomUUID();
    private static final UUID EPOCH = UUID.randomUUID();
    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID USER_1 = UUID.randomUUID();
    private static final UUID USER_2 = UUID.randomUUID();
    private static final UUID USER_3 = UUID.randomUUID();
    private static final UUID ENTRY_1 = UUID.randomUUID();
    private static final UUID ENTRY_2 = UUID.randomUUID();
    private static final UUID ENTRY_3 = UUID.randomUUID();
    private static final Instant OPENS = Instant.parse("2026-09-27T10:00:00Z");
    private static final Instant CLOSES = OPENS.plusSeconds(60);

    @Test
    @DisplayName("квалификационное окно допускает единственного участника (алгоритм 4)")
    void квалификация_одного() {
        VotingWindow window = VotingWindow.openQualification(TOURNAMENT, EPOCH, CLUSTER,
            "u4pu", 1, List.of(seed(ENTRY_1, USER_1)), OPENS, CLOSES, OPENS);
        assertThat(window.scope()).isEqualTo(WindowScope.QUALIFICATION);
        assertThat(window.epochId()).isEqualTo(EPOCH);
        assertThat(window.clusterId()).isEqualTo(CLUSTER);
        assertThat(window.clusterKey()).isEqualTo("u4pu");
        VotingWindow.QualificationCloseOutcome outcome = window.closeQualification(CLOSES);
        assertThat(outcome.promotedEntryId()).isEqualTo(ENTRY_1);
        assertThat(outcome.eliminatedEntryIds()).isEmpty();
        assertThat(window.participants().iterator().next().result())
            .isEqualTo(ParticipantResult.PROMOTED);
    }

    @Test
    @DisplayName("закрытие квалификации: top-1 PROMOTED, остальные ELIMINATED (алгоритм 3)")
    void квалификация_top1() {
        VotingWindow window = qualification3();
        window.castVote(VotingSubject.user(USER_3), ENTRY_1, VoteValue.LIKE, OPENS);
        // DISLIKE фиксирует порядок выбывших: без него ничья 0/0 решается случайным entryId
        window.castVote(VotingSubject.user(USER_1), ENTRY_3, VoteValue.DISLIKE, OPENS);
        VotingWindow.QualificationCloseOutcome outcome = window.closeQualification(CLOSES);
        assertThat(outcome.promotedEntryId()).isEqualTo(ENTRY_1);
        assertThat(outcome.eliminatedEntryIds()).containsExactly(ENTRY_2, ENTRY_3);
    }

    @Test
    @DisplayName("закрытие финала: n=3 → выбывает max(1, floor(3/2)) = 1 худший (алгоритм 7)")
    void финал_выбывание() {
        VotingWindow window = final3();
        window.castVote(VotingSubject.user(USER_3), ENTRY_1, VoteValue.LIKE, OPENS);
        // DISLIKE фиксирует худшего: без него ничья 0/0 решается случайным entryId
        window.castVote(VotingSubject.user(USER_3), ENTRY_2, VoteValue.DISLIKE, OPENS);
        VotingWindow.FinalCloseOutcome outcome = window.closeFinal(CLOSES);
        assertThat(outcome.eliminatedEntryIds()).containsExactly(ENTRY_2);
        assertThat(outcome.survivedEntryIds()).containsExactly(ENTRY_1, ENTRY_3);
    }

    @Test
    @DisplayName("финал n=2 → выбывает 1; n=1 → лидер остаётся SURVIVED без выбывания")
    void финал_крайние_случаи() {
        VotingWindow two = VotingWindow.openFinal(TOURNAMENT, 1,
            List.of(seed(ENTRY_1, USER_1), seed(ENTRY_2, USER_2)), OPENS, CLOSES, OPENS);
        // повторное закрытие — ошибка состояния, поэтому итог фиксируется один раз
        VotingWindow.FinalCloseOutcome twoOutcome = two.closeFinal(CLOSES);
        assertThat(twoOutcome.eliminatedEntryIds()).hasSize(1);
        assertThat(twoOutcome.survivedEntryIds()).hasSize(1);

        VotingWindow single = VotingWindow.openFinal(TOURNAMENT, 1,
            List.of(seed(ENTRY_1, USER_1)), OPENS, CLOSES, OPENS);
        VotingWindow.FinalCloseOutcome outcome = single.closeFinal(CLOSES);
        assertThat(outcome.eliminatedEntryIds()).isEmpty();
        assertThat(outcome.survivedEntryIds()).isEmpty(); // лидер: результат SURVIVED
        assertThat(single.participants().iterator().next().result())
            .isEqualTo(ParticipantResult.SURVIVED);
    }

    @Test
    @DisplayName("закрытие не по scope и до дедлайна — ошибка состояния; повтор — ошибка")
    void ошибки_состояния() {
        VotingWindow qualification = qualification3();
        assertThatThrownBy(() -> qualification.closeFinal(CLOSES))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> qualification.closeQualification(CLOSES.minusNanos(1)))
            .isInstanceOf(IllegalStateException.class);
        qualification.closeQualification(CLOSES);
        assertThatThrownBy(() -> qualification.closeQualification(CLOSES.plusSeconds(1)))
            .isInstanceOf(IllegalStateException.class);
    }

    private VotingWindow qualification3() {
        return VotingWindow.openQualification(TOURNAMENT, EPOCH, CLUSTER, "u4pu", 1,
            List.of(seed(ENTRY_1, USER_1), seed(ENTRY_2, USER_2), seed(ENTRY_3, USER_3)),
            OPENS, CLOSES, OPENS);
    }

    private VotingWindow final3() {
        return VotingWindow.openFinal(TOURNAMENT, 1,
            List.of(seed(ENTRY_1, USER_1), seed(ENTRY_2, USER_2), seed(ENTRY_3, USER_3)),
            OPENS, CLOSES, OPENS);
    }

    private VotingWindow.ParticipantSeed seed(UUID entryId, UUID userId) {
        return new VotingWindow.ParticipantSeed(entryId, userId,
            Instant.parse("2026-09-27T09:00:00Z"));
    }
}
