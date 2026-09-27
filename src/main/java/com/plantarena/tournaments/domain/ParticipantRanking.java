package com.plantarena.tournaments.domain;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Итоговый рейтинг окна (допущение 8): score DESC, joinedAt ASC, entryId ASC.
 * Детерминирован; при отсутствии голосов действует тот же порядок.
 */
public final class ParticipantRanking {

    private static final Comparator<WindowParticipant> ORDER =
        Comparator.comparingLong(WindowParticipant::score).reversed()
            .thenComparing(WindowParticipant::joinedAt)
            .thenComparing(WindowParticipant::entryId);

    private ParticipantRanking() {
    }

    /** От лучшего к худшему. */
    public static List<WindowParticipant> rank(Collection<WindowParticipant> participants) {
        return participants.stream().sorted(ORDER).toList();
    }
}
