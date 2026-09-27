package com.plantarena.moderation.adapter.out.persistence;

import com.plantarena.moderation.domain.ModerationJob;
import com.plantarena.moderation.domain.ModerationJobRepository;
import com.plantarena.moderation.domain.ModerationJobStatus;
import com.plantarena.moderation.domain.ModerationReasonCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация порта ModerationJobRepository на JPA + PostgreSQL (раздел 14.2).
 * find-or-create + saveAndFlush (паттерн JpaPlantRepository): конкурентный
 * захват due-заданий ловит @Version в БД. Сортировка due: nextAttemptAt, id.
 */
@Repository
@Transactional
public class JpaModerationJobRepository implements ModerationJobRepository {

    private static final List<String> DUE_STATUSES = List.of("NEW", "RETRY");

    private final ModerationJobJpaRepository jobs;

    public JpaModerationJobRepository(ModerationJobJpaRepository jobs) {
        this.jobs = jobs;
    }

    @Override
    public ModerationJob save(ModerationJob job) {
        ModerationJobJpaEntity entity = jobs.findById(job.id())
            .orElseGet(() -> new ModerationJobJpaEntity(job.id(), job.plantId(), job.assetId(),
                job.status().name(), job.attempts(), job.nextAttemptAt(), job.createdAt()));
        entity.update(job.status().name(), job.attempts(), job.nextAttemptAt(),
            job.modelVersion(), job.confidence(),
            job.reasonCode() == null ? null : job.reasonCode().name(),
            job.startedAt(), job.completedAt());
        return toDomain(jobs.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ModerationJob> findById(UUID id) {
        return jobs.findById(id).map(JpaModerationJobRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModerationJob> findDue(Instant now, int limit) {
        return jobs.findByStatusInAndNextAttemptAtLessThanEqual(DUE_STATUSES, now,
                PageRequest.of(0, limit, Sort.by("nextAttemptAt").ascending()
                    .and(Sort.by("id")))).stream()
            .map(JpaModerationJobRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ModerationJob> findLatestByPlantId(UUID plantId) {
        return jobs.findFirstByPlantIdOrderByCreatedAtDesc(plantId)
            .map(JpaModerationJobRepository::toDomain);
    }

    private static ModerationJob toDomain(ModerationJobJpaEntity entity) {
        return ModerationJob.restore(entity.getId(), entity.getPlantId(), entity.getAssetId(),
            ModerationJobStatus.valueOf(entity.getStatus()), entity.getAttempts(),
            entity.getNextAttemptAt(), entity.getModelVersion(), entity.getConfidence(),
            entity.getReasonCode() == null ? null : ModerationReasonCode.valueOf(entity.getReasonCode()),
            entity.getStartedAt(), entity.getCompletedAt(), entity.getCreatedAt(),
            entity.getVersion());
    }
}
