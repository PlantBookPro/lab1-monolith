package com.plantarena.plants.application.support;

import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory фейк для application-тестов и контрактных тестов репозитория. */
public class InMemoryPlantRepository implements PlantRepository {

    public final Map<UUID, Plant> plants = new ConcurrentHashMap<>();

    @Override
    public Plant save(Plant plant) {
        plants.put(plant.id(), plant);
        return plant;
    }

    @Override
    public Optional<Plant> findById(UUID id) {
        return Optional.ofNullable(plants.get(id));
    }

    @Override
    public Optional<Plant> findActiveByAssetId(UUID assetId) {
        return plants.values().stream()
            .filter(plant -> plant.assetId().equals(assetId) && plant.archivedAt() == null)
            .findFirst();
    }

    /** Порядок как в PostgreSQL uuid: побайтовый = сравнение id как строки. */
    private static final Comparator<Plant> BY_ID_AS_STRING =
        Comparator.comparing(plant -> plant.id().toString());

    @Override
    public List<Plant> findByOwner(UUID ownerId, int offset, int limit) {
        return plants.values().stream()
            .filter(plant -> plant.ownerId().equals(ownerId) && plant.archivedAt() == null)
            .sorted(BY_ID_AS_STRING)
            .skip(offset)
            .limit(limit)
            .toList();
    }

    @Override
    public long countByOwner(UUID ownerId) {
        return plants.values().stream()
            .filter(plant -> plant.ownerId().equals(ownerId) && plant.archivedAt() == null)
            .count();
    }

    @Override
    public List<Plant> findApprovedByOwner(UUID ownerId, int offset, int limit) {
        return plants.values().stream()
            .filter(plant -> plant.ownerId().equals(ownerId) && plant.archivedAt() == null
                && plant.moderationStatus() == ModerationStatus.APPROVED)
            .sorted(BY_ID_AS_STRING)
            .skip(offset)
            .limit(limit)
            .toList();
    }

    @Override
    public long countApprovedByOwner(UUID ownerId) {
        return plants.values().stream()
            .filter(plant -> plant.ownerId().equals(ownerId) && plant.archivedAt() == null
                && plant.moderationStatus() == ModerationStatus.APPROVED)
            .count();
    }
}
