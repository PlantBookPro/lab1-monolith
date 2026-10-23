package com.plantarena.moderation.application.support;

import com.plantarena.moderation.domain.ModerationJob;
import com.plantarena.moderation.domain.ModerationJobRepository;
import com.plantarena.moderation.domain.ModerationJobStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory фейк для application-тестов и контрактных тестов репозитория. */
public class InMemoryModerationJobRepository implements ModerationJobRepository {

    public final Map<UUID, ModerationJob> jobs = new ConcurrentHashMap<>();

    private static final Comparator<ModerationJob> BY_NEXT_ATTEMPT_THEN_ID =
        Comparator.comparing(ModerationJob::nextAttemptAt)
            .thenComparing(job -> job.id().toString());

    @Override
    public ModerationJob save(ModerationJob job) {
        jobs.put(job.id(), job);
        return job;
    }

    @Override
    public Optional<ModerationJob> findById(UUID id) {
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public List<ModerationJob> findDue(Instant now, int limit) {
        return jobs.values().stream()
            .filter(job -> (job.status() == ModerationJobStatus.NEW
                    || job.status() == ModerationJobStatus.RETRY)
                && !job.nextAttemptAt().isAfter(now))
            .sorted(BY_NEXT_ATTEMPT_THEN_ID)
            .limit(limit)
            .toList();
    }

    @Override
    public Optional<ModerationJob> findLatestByPlantId(UUID plantId) {
        return jobs.values().stream()
            .filter(job -> job.plantId().equals(plantId))
            .max(Comparator.comparing(ModerationJob::createdAt)
                .thenComparing(job -> job.id().toString()));
    }
}
