package com.plantarena.tournaments;

import com.plantarena.tournaments.domain.GuestSession;
import com.plantarena.tournaments.domain.GuestSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт репозитория GuestSession: фейк и JPA-адаптер ведут себя одинаково
 * (честность фейка из application-тестов). @Transactional на классе — урок
 * итерации 2.
 */
@Transactional
@DisplayName("Контракт GuestSessionRepository: save + findByTokenHash")
public abstract class GuestSessionRepositoryContractTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    protected abstract GuestSessionRepository repository();

    @Test
    @DisplayName("save + findByTokenHash: сессия восстанавливается по хэшу")
    void roundtrip_по_хэшу() {
        UUID id = UUID.randomUUID();
        String hash = "b".repeat(64);
        repository().save(GuestSession.issue(id, hash, NOW, Duration.ofHours(24)));

        GuestSession restored = repository().findByTokenHash(hash).orElseThrow();

        assertThat(restored.id()).isEqualTo(id);
        assertThat(restored.tokenHash()).isEqualTo(hash);
        assertThat(restored.createdAt()).isEqualTo(NOW);
        assertThat(restored.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(restored.isActive(NOW)).isTrue();
    }

    @Test
    @DisplayName("неизвестный хэш — пусто; хэши не коллидируют")
    void неизвестный_хэш() {
        repository().save(GuestSession.issue(UUID.randomUUID(), "c".repeat(64), NOW,
            Duration.ofHours(1)));
        assertThat(repository().findByTokenHash("d".repeat(64))).isEmpty();
    }
}
