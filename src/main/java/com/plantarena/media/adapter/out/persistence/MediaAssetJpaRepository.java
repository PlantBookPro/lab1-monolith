package com.plantarena.media.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;


public interface MediaAssetJpaRepository extends JpaRepository<MediaAssetJpaEntity, UUID> {
}
