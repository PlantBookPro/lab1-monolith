package com.plantarena.media.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data репозиторий JPA-модели media_asset (только внутри адаптера, правило 10.2.6). */
public interface MediaAssetJpaRepository extends JpaRepository<MediaAssetJpaEntity, UUID> {
}
