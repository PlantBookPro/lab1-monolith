package com.plantarena.tournaments.adapter.in.web;

import java.time.Instant;
import java.util.UUID;


public record GlobalInfoResponse(long epochDurationSeconds, long finalWindowDurationSeconds,
                                 EpochInfo currentEpoch, WindowInfo currentFinalWindow) {

    record EpochInfo(UUID id, int sequence, Instant opensAt, Instant closesAt) {
    }

    record WindowInfo(UUID id, int sequence, Instant opensAt, Instant closesAt) {
    }
}
