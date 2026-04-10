package com.plantarena.tournaments.adapter.in.web;

import java.time.Instant;
import java.util.UUID;

/** Кластер текущей эпохи (раздел 13): без приватных данных. */
public record GlobalClusterResponse(UUID clusterId, String clusterKey, UUID windowId,
                                    int memberCount, Instant closesAt) {
}
