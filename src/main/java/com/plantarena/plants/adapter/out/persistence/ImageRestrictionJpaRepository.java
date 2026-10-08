package com.plantarena.plants.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;


public interface ImageRestrictionJpaRepository extends JpaRepository<ImageRestrictionJpaEntity, UUID> {

    List<ImageRestrictionJpaEntity> findByOwnerIdAndFingerprint(UUID ownerId, String fingerprint);
}
