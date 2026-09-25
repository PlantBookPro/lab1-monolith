package com.plantarena.media.adapter.out.persistence;

import com.plantarena.media.application.port.out.AssetClaimRepository;
import com.plantarena.media.domain.AssetClaim;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация порта AssetClaimRepository на JPA + PostgreSQL (раздел 14.2).
 * save с существующим asset_id — merge (upsert публичности по решению
 * модерации); с новым — insert.
 */
@Repository
@Transactional
public class JpaAssetClaimRepository implements AssetClaimRepository {

    private final AssetClaimJpaRepository jpaRepository;

    public JpaAssetClaimRepository(AssetClaimJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AssetClaim> findByAssetId(UUID assetId) {
        return jpaRepository.findById(assetId).map(JpaAssetClaimRepository::toDomain);
    }

    @Override
    public AssetClaim save(AssetClaim claim) {
        return toDomain(jpaRepository.saveAndFlush(new AssetClaimJpaEntity(
            claim.assetId(), claim.plantId(), claim.publiclyVisible(), claim.claimedAt())));
    }

    @Override
    public void deleteByAssetId(UUID assetId) {
        jpaRepository.deleteById(assetId);
    }

    private static AssetClaim toDomain(AssetClaimJpaEntity entity) {
        return AssetClaim.claimed(entity.getAssetId(), entity.getPlantId(),
            entity.isPubliclyVisible(), entity.getClaimedAt());
    }
}
