package com.plantarena.plants.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data для plant; «активное» = не архивировано (ADR-008). */
public interface PlantJpaRepository extends JpaRepository<PlantJpaEntity, UUID> {

    Optional<PlantJpaEntity> findByAssetIdAndArchivedAtIsNull(UUID assetId);

    List<PlantJpaEntity> findByOwnerIdAndArchivedAtIsNull(UUID ownerId, Pageable pageable);

    long countByOwnerIdAndArchivedAtIsNull(UUID ownerId);

    List<PlantJpaEntity> findByOwnerIdAndArchivedAtIsNullAndModerationStatus(
        UUID ownerId, String moderationStatus, Pageable pageable);

    long countByOwnerIdAndArchivedAtIsNullAndModerationStatus(
        UUID ownerId, String moderationStatus);
}
