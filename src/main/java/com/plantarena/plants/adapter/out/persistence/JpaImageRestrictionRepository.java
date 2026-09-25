package com.plantarena.plants.adapter.out.persistence;

import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.plants.domain.ImageRestrictionRepository;
import com.plantarena.plants.domain.RestrictionKind;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация порта ImageRestrictionRepository на JPA + PostgreSQL (раздел 14.2).
 * Append-only: save только создаёт записи, история не удаляется (раздел 6).
 */
@Repository
@Transactional
public class JpaImageRestrictionRepository implements ImageRestrictionRepository {

    private final ImageRestrictionJpaRepository restrictions;

    public JpaImageRestrictionRepository(ImageRestrictionJpaRepository restrictions) {
        this.restrictions = restrictions;
    }

    @Override
    public ImageRestriction save(ImageRestriction restriction) {
        return toDomain(restrictions.saveAndFlush(new ImageRestrictionJpaEntity(
            restriction.id(), restriction.ownerId(), restriction.fingerprint().value(),
            restriction.fingerprint().algorithmVersion(), restriction.kind().name(),
            restriction.expiresAt(), restriction.reason(), restriction.sourceEntryId(),
            restriction.createdAt())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImageRestriction> findByOwnerAndFingerprint(UUID ownerId, String fingerprintValue) {
        return restrictions.findByOwnerIdAndFingerprint(ownerId, fingerprintValue).stream()
            .map(JpaImageRestrictionRepository::toDomain).toList();
    }

    private static ImageRestriction toDomain(ImageRestrictionJpaEntity entity) {
        return ImageRestriction.restore(entity.getId(), entity.getOwnerId(),
            new ImageFingerprint(entity.getFingerprint(), entity.getFingerprintVersion()),
            RestrictionKind.valueOf(entity.getKind()), entity.getExpiresAt(),
            entity.getReason(), entity.getSourceEntryId(), entity.getCreatedAt());
    }
}
