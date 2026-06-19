package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Инварианты окна голосования (разделы 7, 9, 12.1). */
@DisplayName("Окно голосования: состав, дельты, самоголосование, полуоткрытый интервал")
class VotingWindowTest {

    private static final UUID TOURNAMENT = UUID.randomUUID();
    private static final UUID USER_1 = UUID.randomUUID();
    private static final UUID USER_2 = UUID.randomUUID();
    private static final UUID USER_3 = UUID.randomUUID();
    // фиксированные возрастающие id: тай-брейк entryId ASC детерминирован (ENTRY_3 — больший)
    private static final UUID ENTRY_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ENTRY_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ENTRY_3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant OPENS = Instant.parse("2026-09-27T10:00:00Z");
    private static final Instant CLOSES = OPENS.plusSeconds(60);

    @Test
    @DisplayName("окно private-турнира открывается минимум с двумя участниками")
    void окно_минимум_с_двумя() {
        List<VotingWindow.ParticipantSeed> one = List.of(seed(ENTRY_1, USER_1));
        assertThatThrownBy(() ->
            VotingWindow.open(TOURNAMENT, 1, one, OPENS, CLOSES, OPENS))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("новый LIKE даёт +1, второй субъект LIKE даёт +1")
    void новые_голоса() {
        VotingWindow window = window3();
        assertThat(window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.LIKE, OPENS))
            .isEqualTo(1L);
        assertThat(window.castVote(VotingSubject.user(USER_3), ENTRY_2, VoteValue.LIKE, OPENS))
            .isEqualTo(2L);
    }

    @Test
    @DisplayName("повтор LIKE после LIKE не меняет счёт")
    void повтор_того_же_значения() {
        VotingWindow window = window3();
        window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.LIKE, OPENS);
        assertThat(window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.LIKE, OPENS))
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("LIKE → DISLIKE меняет счёт на −2")
    void смена_знака() {
        VotingWindow window = window3();
        window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.LIKE, OPENS);
        assertThat(window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.DISLIKE, OPENS))
            .isEqualTo(-1L);
    }

    @Test
    @DisplayName("удаление голоса компенсирует вклад; повторное удаление безопасно")
    void удаление_компенсирует() {
        VotingWindow window = window3();
        window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.DISLIKE, OPENS);
        assertThat(window.removeVote(VotingSubject.user(USER_1), ENTRY_2, OPENS)).isZero();
        assertThat(window.removeVote(VotingSubject.user(USER_1), ENTRY_2, OPENS)).isZero();
    }

    @Test
    @DisplayName("score равен сумме текущих голосов окна и entry (инвариант)")
    void score_равен_сумме_голосов() {
        VotingWindow window = window3();
        window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.LIKE, OPENS);
        window.castVote(VotingSubject.user(USER_2), ENTRY_3, VoteValue.DISLIKE, OPENS);
        window.castVote(VotingSubject.user(USER_3), ENTRY_2, VoteValue.DISLIKE, OPENS);
        window.castVote(VotingSubject.user(USER_3), ENTRY_2, VoteValue.LIKE, OPENS);
        window.removeVote(VotingSubject.user(USER_1), ENTRY_2, OPENS);
        long sum = window.votesOf(ENTRY_2).stream()
            .mapToLong(vote -> vote.value().contribution()).sum();
        assertThat(window.scoreOf(ENTRY_2)).isEqualTo(sum).isEqualTo(1L);
    }

    @Test
    @DisplayName("самоголосование запрещено для идентифицированного пользователя (допущение 6)")
    void самоголосование_запрещено() {
        VotingWindow window = window3();
        assertThatThrownBy(() ->
            window.castVote(VotingSubject.user(USER_1), ENTRY_1, VoteValue.LIKE, OPENS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Самоголосование");
    }

    @Test
    @DisplayName("голос за entry вне окна и за чужое окно — ошибка состояния")
    void entry_вне_окна() {
        VotingWindow window = window3();
        assertThatThrownBy(() -> window.castVote(VotingSubject.user(USER_1),
            UUID.randomUUID(), VoteValue.LIKE, OPENS))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("интервал [opensAt, closesAt): в closesAt новый голос уже запрещён")
    void полуоткрытый_интервал() {
        VotingWindow window = window3();
        assertThat(window.isAcceptingVotes(CLOSES.minusNanos(1))).isTrue();
        assertThat(window.isAcceptingVotes(CLOSES)).isFalse();
        assertThatThrownBy(() ->
            window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.LIKE, CLOSES))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Дедлайн");
    }

    @Test
    @DisplayName("myVote возвращает текущее значение или null")
    void my_vote() {
        VotingWindow window = window3();
        String subject = VotingSubject.user(USER_1).subjectKey();
        assertThat(window.myVote(subject, ENTRY_2)).isNull();
        window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.LIKE, OPENS);
        assertThat(window.myVote(subject, ENTRY_2)).isEqualTo(VoteValue.LIKE);
    }

    @Test
    @DisplayName("закрытие до closesAt невозможно")
    void закрытие_до_дедлайна() {
        VotingWindow window = window3();
        assertThatThrownBy(() -> window.close(CLOSES.minusSeconds(1),
            new RoundElimination(), 0.5))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("открыто");
    }

    @Test
    @DisplayName("закрытие: 3 участника, f=0.5 → 1 худший ELIMINATED, 2 SURVIVED")
    void закрытие_с_выбыванием() {
        VotingWindow window = window3();
        window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.LIKE, OPENS);
        window.castVote(VotingSubject.user(USER_2), ENTRY_1, VoteValue.DISLIKE, OPENS);
        // счёт: entry2 +1, entry3 0, entry1 −1 → выбывает entry1

        VotingWindow.CloseOutcome outcome = window.close(CLOSES, new RoundElimination(), 0.5);

        assertThat(outcome.eliminatedEntryIds()).containsExactly(ENTRY_1);
        assertThat(outcome.survivedEntryIds()).containsExactly(ENTRY_2, ENTRY_3);
        assertThat(outcome.winnerEntryId()).isNull();
        assertThat(window.status()).isEqualTo(WindowStatus.CLOSED);
        assertThat(window.scoreOf(ENTRY_1)).isEqualTo(-1L); // итоги не переписываются
    }

    @Test
    @DisplayName("закрытие при одном выжившем: WINNER, окно с одним участником не создаётся")
    void закрытие_с_победителем() {
        VotingWindow window = VotingWindow.open(TOURNAMENT, 2, List.of(
            seed(ENTRY_2, USER_2), seed(ENTRY_3, USER_3)), OPENS, CLOSES, OPENS);
        window.castVote(VotingSubject.user(USER_2), ENTRY_3, VoteValue.LIKE, OPENS);
        // счёт: entry3 +1, entry2 0 → выбывает entry2 (f=0.5 → 1)

        VotingWindow.CloseOutcome outcome = window.close(CLOSES, new RoundElimination(), 0.5);

        assertThat(outcome.eliminatedEntryIds()).containsExactly(ENTRY_2);
        assertThat(outcome.survivedEntryIds()).isEmpty();
        assertThat(outcome.winnerEntryId()).isEqualTo(ENTRY_3);
    }

    @Test
    @DisplayName("повторное закрытие не меняет результатов (ошибка состояния; идемпотентность — use case)")
    void повторное_закрытие() {
        VotingWindow window = window3();
        window.close(CLOSES, new RoundElimination(), 0.5);
        assertThatThrownBy(() -> window.close(CLOSES.plusSeconds(1), new RoundElimination(), 0.5))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("уже закрыто");
    }

    @Test
    @DisplayName("ничья решается детерминированно: score DESC, joinedAt ASC, entryId ASC")
    void ничья_при_закрытии() {
        // все score 0, joinedAt равны → выбывает больший entryId
        VotingWindow window = window3();
        VotingWindow.CloseOutcome outcome = window.close(CLOSES, new RoundElimination(), 0.5);
        assertThat(outcome.eliminatedEntryIds()).containsExactly(ENTRY_3);
    }

    private VotingWindow window3() {
        return VotingWindow.open(TOURNAMENT, 1, List.of(
            seed(ENTRY_1, USER_1), seed(ENTRY_2, USER_2), seed(ENTRY_3, USER_3)),
            OPENS, CLOSES, OPENS);
    }

    private VotingWindow.ParticipantSeed seed(UUID entryId, UUID userId) {
        return new VotingWindow.ParticipantSeed(entryId, userId,
            Instant.parse("2026-09-27T09:00:00Z"));
    }
}
