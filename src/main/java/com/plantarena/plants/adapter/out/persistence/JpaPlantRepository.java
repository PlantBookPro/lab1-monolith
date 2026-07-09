package com.plantarena.plants.adapter.out.persistence;

import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.LifeStatus;
import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация порта PlantRepository на JPA + PostgreSQL (раздел 14.2).
 * find-or-create + update + saveAndFlush (паттерн JpaUserRepository):
 * конкурентные обновления ловит @Version в БД. Сортировка по id —
 * детерминированная пагинация с tie-break (раздел 13).
 */
@Repository
@Transactional
public class JpaPlantRepository implements PlantRepository {

    private final PlantJpaRepository plants;

    public JpaPlantRepository(PlantJpaRepository plants) {
        this.plants = plants;
    }

    @Override
    public Plant save(Plant plant) {
        PlantJpaEntity entity = plants.findById(plant.id())
            .orElseGet(() -> new PlantJpaEntity(plant.id(), plant.ownerId(), plant.assetId(),
                plant.fingerprint().value(), plant.fingerprint().algorithmVersion(),
                plant.title(), plant.createdAt()));
        entity.update(plant.title(), plant.moderationStatus().name(), plant.moderationReason(),
            plant.lifeStatus().name(), plant.diedAt(), plant.archivedAt());
        return toDomain(plants.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Plant> findById(UUID id) {
        return plants.findById(id).map(JpaPlantRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Plant> findActiveByAssetId(UUID assetId) {
        return plants.findByAssetIdAndArchivedAtIsNull(assetId)
            .map(JpaPlantRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Plant> findByOwner(UUID ownerId, int offset, int limit) {
        // offset всегда page-aligned (page * size из use case, как в JpaUserRepository)
        return plants.findByOwnerIdAndArchivedAtIsNull(ownerId, page(offset, limit)).stream()
            .map(JpaPlantRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByOwner(UUID ownerId) {
        return plants.countByOwnerIdAndArchivedAtIsNull(ownerId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Plant> findApprovedByOwner(UUID ownerId, int offset, int limit) {
        return plants.findByOwnerIdAndArchivedAtIsNullAndModerationStatus(ownerId,
                ModerationStatus.APPROVED.name(), page(offset, limit)).stream()
            .map(JpaPlantRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countApprovedByOwner(UUID ownerId) {
        return plants.countByOwnerIdAndArchivedAtIsNullAndModerationStatus(ownerId,
            ModerationStatus.APPROVED.name());
    }

    private static PageRequest page(int offset, int limit) {
        return PageRequest.of(offset / limit, limit, Sort.by("id"));
    }

    private static Plant toDomain(PlantJpaEntity entity) {
        return Plant.restore(entity.getId(), entity.getOwnerId(), entity.getAssetId(),
            new ImageFingerprint(entity.getFingerprint(), entity.getFingerprintVersion()),
            entity.getTitle(), ModerationStatus.valueOf(entity.getModerationStatus()),
            entity.getModerationReason(), LifeStatus.valueOf(entity.getLifeStatus()),
            entity.getCreatedAt(), entity.getDiedAt(), entity.getArchivedAt(),
            entity.getVersion());
    }
}
