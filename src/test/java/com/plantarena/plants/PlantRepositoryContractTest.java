package com.plantarena.plants;

import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт PlantRepository (раздел 14.2): одинаковые гарантии у in-memory
 * фейка (application-тесты) и JPA + PostgreSQL (adapter-тесты) — честность фейка.
 *
 * <p>@Transactional обязателен на самом базовом классе: аннотация из @DataJpaTest
 * на подклассе не применяется к наследуемым тест-методам, и без неё JPA-контрактные
 * тесты автокоммитят и протекают в общую БД между контекстами (урок итерации 2,
 * фикс 56e8e3a — см. UserRepositoryContractTest/MediaAssetRepositoryContractTest).
 */
@DisplayName("Контракт PlantRepository")
@Transactional
public abstract class PlantRepositoryContractTest {

    protected static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    protected abstract PlantRepository repository();

    protected ImageFingerprint fingerprint(char digit) {
        return new ImageFingerprint("0".repeat(63) + digit, 1);
    }

    protected Plant submittedPlant(UUID ownerId, char fingerprintDigit) {
        return Plant.submit(ownerId, UUID.randomUUID(), fingerprint(fingerprintDigit),
            "Фикус", NOW);
    }

    @Test
    @DisplayName("сохранение и чтение по id: все поля")
    void сохранение_и_чтение_по_id() {
        Plant plant = submittedPlant(UUID.randomUUID(), '1');

        repository().save(plant);
        Plant loaded = repository().findById(plant.id()).orElseThrow();

        assertThat(loaded.id()).isEqualTo(plant.id());
        assertThat(loaded.ownerId()).isEqualTo(plant.ownerId());
        assertThat(loaded.assetId()).isEqualTo(plant.assetId());
        assertThat(loaded.fingerprint()).isEqualTo(plant.fingerprint());
        assertThat(loaded.title()).isEqualTo("Фикус");
        assertThat(loaded.moderationStatus()).isEqualTo(ModerationStatus.PENDING);
        assertThat(loaded.moderationReason()).isNull();
        assertThat(loaded.lifeStatus().name()).isEqualTo("ALIVE");
        assertThat(loaded.createdAt()).isEqualTo(NOW);
        assertThat(loaded.diedAt()).isNull();
        assertThat(loaded.archivedAt()).isNull();
    }

    @Test
    @DisplayName("мутации модерации, жизни и архивации переживают сохранение")
    void мутации_переживают_сохранение() {
        Plant plant = submittedPlant(UUID.randomUUID(), '2');
        repository().save(plant);

        plant.applyDecision(ModerationStatus.REJECTED, "не растение");
        plant.die(NOW.plusSeconds(60));
        plant.archive(NOW.plusSeconds(120));
        repository().save(plant);

        Plant loaded = repository().findById(plant.id()).orElseThrow();
        assertThat(loaded.moderationStatus()).isEqualTo(ModerationStatus.REJECTED);
        assertThat(loaded.moderationReason()).isEqualTo("не растение");
        assertThat(loaded.lifeStatus().name()).isEqualTo("DEAD");
        assertThat(loaded.diedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(loaded.archivedAt()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    @DisplayName("несуществующий id — пустой результат")
    void несуществующий_id_пустой_результат() {
        assertThat(repository().findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("активное растение на файл; архивация освобождает файл (ADR-008)")
    void активное_растение_на_файл_архивация_освобождает() {
        UUID assetId = UUID.randomUUID();
        Plant plant = Plant.submit(UUID.randomUUID(), assetId, fingerprint('3'), "Фикус", NOW);
        repository().save(plant);

        assertThat(repository().findActiveByAssetId(assetId)).isPresent();

        plant.archive(NOW);
        repository().save(plant);

        assertThat(repository().findActiveByAssetId(assetId)).isEmpty();
    }

    @Test
    @DisplayName("список владельца: пагинация, сортировка по id, архив скрыт")
    void список_владельца_пагинация_и_архив() {
        UUID ownerId = UUID.randomUUID();
        for (int i = 1; i <= 3; i++) {
            repository().save(Plant.submit(ownerId, UUID.randomUUID(),
                fingerprint(Character.forDigit(i, 10)), "Растение " + i, NOW));
        }

        List<Plant> firstPage = repository().findByOwner(ownerId, 0, 2);
        assertThat(firstPage).hasSize(2);
        assertThat(repository().findByOwner(ownerId, 2, 2)).hasSize(1);
        assertThat(repository().countByOwner(ownerId)).isEqualTo(3);

        // порядок сравнения UUID как строки совпадает с побайтовым порядком
        // PostgreSQL uuid — оба наследника контракта упорядочены одинаково
        List<UUID> ids = new ArrayList<>();
        repository().findByOwner(ownerId, 0, 2).forEach(plant -> ids.add(plant.id()));
        repository().findByOwner(ownerId, 2, 2).forEach(plant -> ids.add(plant.id()));
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).isSortedAccordingTo(Comparator.comparing(UUID::toString));

        Plant archived = firstPage.get(0);
        archived.archive(NOW);
        repository().save(archived);

        assertThat(repository().countByOwner(ownerId)).isEqualTo(2);
    }

    @Test
    @DisplayName("публичный список: только APPROVED без архивированных (ADR-008)")
    void публичный_список_только_approved() {
        UUID ownerId = UUID.randomUUID();
        Plant pending = submittedPlant(ownerId, '4');
        Plant approved = submittedPlant(ownerId, '5');
        approved.applyDecision(ModerationStatus.APPROVED, null);
        Plant archivedApproved = submittedPlant(ownerId, '6');
        archivedApproved.applyDecision(ModerationStatus.APPROVED, null);
        archivedApproved.archive(NOW);
        repository().save(pending);
        repository().save(approved);
        repository().save(archivedApproved);

        List<Plant> publicList = repository().findApprovedByOwner(ownerId, 0, 20);

        assertThat(publicList).hasSize(1);
        assertThat(publicList.get(0).id()).isEqualTo(approved.id());
        assertThat(repository().countApprovedByOwner(ownerId)).isEqualTo(1);
    }
}
