package com.plantarena.tournaments.domain;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;


public final class ParticipantRanking {

    private static final Comparator<WindowParticipant> ORDER =
        Comparator.comparingLong(WindowParticipant::score).reversed()
            .thenComparing(WindowParticipant::joinedAt)
            .thenComparing(WindowParticipant::entryId);

    private ParticipantRanking() {
    }

    
    public static List<WindowParticipant> rank(Collection<WindowParticipant> participants) {
        return participants.stream().sorted(ORDER).toList();
    }
}
