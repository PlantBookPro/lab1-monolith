package com.plantarena.tournaments.domain;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Субъект голосования (раздел 9): USER и GUEST, стабильный subjectKey. */
@DisplayName("VotingSubject: USER и GUEST")
class VotingSubjectTest {

    @Test
    @DisplayName("гость: ключ GUEST:<sessionId>, самоголосование неприменимо")
    void гостевой_субъект() {
        UUID sessionId = UUID.randomUUID();
        VotingSubject guest = VotingSubject.guest(sessionId);
        assertThat(guest.subjectKey()).isEqualTo("GUEST:" + sessionId);
        assertThat(guest.isUser(UUID.randomUUID())).isFalse();
        assertThat(guest.sessionId()).isEqualTo(sessionId);
        assertThat(guest.userId()).isNull();
    }

    @Test
    @DisplayName("пользователь: ключ USER:<userId>, гость — не он")
    void пользовательский_субъект() {
        UUID userId = UUID.randomUUID();
        VotingSubject user = VotingSubject.user(userId);
        assertThat(user.subjectKey()).isEqualTo("USER:" + userId);
        assertThat(user.isUser(userId)).isTrue();
        assertThat(user.isUser(UUID.randomUUID())).isFalse();
        assertThat(user.sessionId()).isNull();
    }

    @Test
    @DisplayName("ключи USER и GUEST не конфликтуют (разные префиксы)")
    void префиксы_ключей() {
        UUID id = UUID.randomUUID();
        assertThat(VotingSubject.user(id).subjectKey())
            .isNotEqualTo(VotingSubject.guest(id).subjectKey());
    }
}
