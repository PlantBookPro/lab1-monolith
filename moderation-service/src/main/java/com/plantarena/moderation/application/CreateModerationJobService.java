package com.plantarena.moderation.application;

import com.plantarena.moderation.application.port.in.CreateModerationJobUseCase;
import com.plantarena.moderation.domain.ModerationJob;
import com.plantarena.moderation.domain.ModerationJobRepository;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class CreateModerationJobService implements CreateModerationJobUseCase {

    private final ModerationJobRepository jobs;
    private final Clock clock;

    @Override
    public void onPlantSubmitted(UUID plantId, UUID assetId) {
        if (jobs.findLatestByPlantId(plantId).isPresent()) {
            return;
        }
        jobs.save(ModerationJob.create(plantId, assetId, clock.instant()));
    }
}
