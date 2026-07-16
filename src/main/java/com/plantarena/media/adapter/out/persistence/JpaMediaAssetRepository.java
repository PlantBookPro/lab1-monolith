package com.plantarena.media.adapter.out.persistence;

import com.plantarena.media.application.port.out.MediaAssetRepository;
import com.plantarena.media.domain.MediaAsset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация порта MediaAssetRepository на JPA + PostgreSQL (раздел 14.2).
 * save — короткая транзакция регистрации метаданных (раздел 12).
 */
@Repository
@Transactional
public class JpaMediaAssetRepository implements MediaAssetRepository {

    private final MediaAssetJpaRepository jpaRepository;

    public JpaMediaAssetRepository(MediaAssetJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public MediaAsset save(MediaAsset asset) {
        return MediaAssetMapper.toDomain(
            jpaRepository.saveAndFlush(MediaAssetMapper.toEntity(asset)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MediaAsset> findById(UUID id) {
        return jpaRepository.findById(id).map(MediaAssetMapper::toDomain);
    }

    @Override
    public void delete(UUID id) {
        jpaRepository.deleteById(id);
    }
}
