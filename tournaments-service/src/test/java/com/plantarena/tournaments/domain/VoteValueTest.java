package com.plantarena.tournaments.domain;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Дельты голосов (раздел 9): чистая функция переходов, исчерпывающие случаи. */
@DisplayName("Дельта счёта при переходе previous → next (null — голоса не было)")
class VoteValueTest {

    static Stream<Arguments> transitions() {
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.arguments(null, VoteValue.LIKE, 1L),
            Arguments.arguments(null, VoteValue.DISLIKE, -1L),
            Arguments.arguments(VoteValue.LIKE, VoteValue.LIKE, 0L),
            Arguments.arguments(VoteValue.DISLIKE, VoteValue.DISLIKE, 0L),
            Arguments.arguments(VoteValue.LIKE, VoteValue.DISLIKE, -2L),
            Arguments.arguments(VoteValue.DISLIKE, VoteValue.LIKE, 2L),
            Arguments.arguments(VoteValue.LIKE, null, -1L),
            Arguments.arguments(VoteValue.DISLIKE, null, 1L),
            Arguments.arguments(null, null, 0L));
    }

    @ParameterizedTest(name = "{0} → {1} = {2}")
    @MethodSource("transitions")
    void дельта_перехода(VoteValue previous, VoteValue next, long expected) {
        assertThat(VoteValue.transitionDelta(previous, next)).isEqualTo(expected);
    }
}
