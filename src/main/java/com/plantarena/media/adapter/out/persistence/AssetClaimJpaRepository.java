package com.plantarena.media.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data для asset_claim; PK — asset_id (один файл — одно растение). */
public interface AssetClaimJpaRepository extends JpaRepository<AssetClaimJpaEntity, UUID> {
}
