package com.plantarena.tournaments.domain;


public enum VoteValue {
    LIKE, DISLIKE;

    
    public long contribution() {
        return this == LIKE ? 1L : -1L;
    }

    
    public static long transitionDelta(VoteValue previous, VoteValue next) {
        if (previous == next) {
            return 0L;
        }
        if (previous == null) {
            return next.contribution();
        }
        if (next == null) {
            return -previous.contribution();
        }
        return next.contribution() - previous.contribution();
    }
}
