package com.plantarena.geo;

import com.plantarena.geo.domain.ClusterMember;
import com.plantarena.geo.domain.ClusterSnapshot;
import com.plantarena.geo.domain.ClusterSnapshotRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контрактный тест репозитория ClusterSnapshot (раздел 14.2): фейк и
 * JPA-адаптер ведут себя одинаково. Наследники JPA — @Transactional на себе
 * (урок итерации 2).
 */
@Transactional
@DisplayName("Контракт ClusterSnapshotRepository")
public abstract class ClusterSnapshotRepositoryContractTest {

    protected abstract ClusterSnapshotRepository repository();

    @Test
    @DisplayName("save → findByEpochId возвращает снимки эпохи; findById — по id")
    void сохранение_и_чтение() {
        ClusterSnapshotRepository repository = repository();
        UUID epochId = UUID.randomUUID();
        ClusterSnapshot first = snapshot(epochId, "u4pu", "e-1");
        ClusterSnapshot second = snapshot(epochId, "u8t", "e-2");
        repository.save(first);
        repository.save(second);

        assertThat(repository.findByEpochId(epochId))
            .extracting(ClusterSnapshot::clusterKey)
            .containsExactlyInAnyOrder("u4pu", "u8t");
        ClusterSnapshot loaded = repository.findById(first.id()).orElseThrow();
        assertThat(loaded.policyVersion()).isEqualTo("geohash-v1-p4");
        assertThat(loaded.members()).isEqualTo(first.members());
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    private ClusterSnapshot snapshot(UUID epochId, String clusterKey, String entrySuffix) {
        UUID entryId = UUID.nameUUIDFromBytes(entrySuffix.getBytes());
        return ClusterSnapshot.fix(epochId, clusterKey, "geohash-v1-p4",
            List.of(new ClusterMember(entryId, UUID.randomUUID(), 55.7558, 37.6173, 1L)),
            Instant.parse("2026-09-27T10:00:00Z"));
    }
}
