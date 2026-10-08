package com.plantarena.media.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;


public interface AssetClaimJpaRepository extends JpaRepository<AssetClaimJpaEntity, UUID> {
}
