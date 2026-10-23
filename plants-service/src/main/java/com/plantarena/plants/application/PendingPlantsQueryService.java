package com.plantarena.plants.application;

import com.plantarena.plants.application.port.in.QueryPendingPlantsUseCase;
import com.plantarena.plants.application.port.in.QueryPendingPlantsUseCase.PendingPlant;
import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.PlantRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
public class PendingPlantsQueryService implements QueryPendingPlantsUseCase {

    private final PlantRepository plants;

    @Override
    @Transactional(readOnly = true)
    public List<PendingPlant> findPending(int limit) {
        return plants.findByModerationStatus(ModerationStatus.PENDING, limit).stream()
            .map(plant -> new PendingPlant(plant.id(), plant.assetId()))
            .toList();
    }
}
