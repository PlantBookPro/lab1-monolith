package com.plantarena.moderation.application;

import com.plantarena.moderation.application.port.in.CreateModerationJobUseCase;
import com.plantarena.moderation.domain.ModerationJob;
import com.plantarena.moderation.domain.ModerationJobRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;


@Service
public class CreateModerationJobService implements CreateModerationJobUseCase {

    private final ModerationJobRepository jobs;
    private final Clock clock;

    public CreateModerationJobService(ModerationJobRepository jobs, Clock clock) {
        this.jobs = jobs;
        this.clock = clock;
    }

    @Override
    public void onPlantSubmitted(UUID plantId, UUID assetId) {
        if (jobs.findLatestByPlantId(plantId).isPresent()) {
            return;
        }
        jobs.save(ModerationJob.create(plantId, assetId, clock.instant()));
    }
}
