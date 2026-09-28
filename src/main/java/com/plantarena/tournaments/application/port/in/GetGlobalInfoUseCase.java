package com.plantarena.tournaments.application.port.in;

import java.time.Instant;
import java.util.UUID;

/** Публичная конфигурация и текущие окна глобального турнира (раздел 13). */
public interface GetGlobalInfoUseCase {

    GlobalInfo globalInfo();

    record GlobalInfo(long epochDurationSeconds, long finalWindowDurationSeconds,
                      EpochInfo currentEpoch, WindowInfo currentFinalWindow) {
    }

    record EpochInfo(UUID id, int sequence, Instant opensAt, Instant closesAt) {
    }

    record WindowInfo(UUID id, int sequence, Instant opensAt, Instant closesAt) {
    }
}
