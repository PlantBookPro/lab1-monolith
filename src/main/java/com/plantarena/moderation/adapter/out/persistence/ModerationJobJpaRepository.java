package com.plantarena.moderation.adapter.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;


public interface ModerationJobJpaRepository extends JpaRepository<ModerationJobJpaEntity, UUID> {

    List<ModerationJobJpaEntity> findByStatusInAndNextAttemptAtLessThanEqual(
        Collection<String> statuses, Instant nextAttemptAt, Pageable pageable);

    Optional<ModerationJobJpaEntity> findFirstByPlantIdOrderByCreatedAtDesc(UUID plantId);
}
