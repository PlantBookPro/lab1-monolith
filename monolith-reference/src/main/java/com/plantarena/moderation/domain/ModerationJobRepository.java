package com.plantarena.moderation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


public interface ModerationJobRepository {

    ModerationJob save(ModerationJob job);

    Optional<ModerationJob> findById(UUID id);

    List<ModerationJob> findDue(Instant now, int limit);

    Optional<ModerationJob> findLatestByPlantId(UUID plantId);
}
