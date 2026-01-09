# Итерация 4 (moderation): ModerationJob, классификатор ONNX, retry без лимита — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Контекст `moderation`: агрегат `ModerationJob` (NEW → IN_PROGRESS → DONE | RETRY, retry без лимита с капом backoff 1ч), обработчик события `PlantSubmitted`, @Scheduled-poller (пачка ≤ 10, короткие tx: захват → инференс вне tx → применение), порт `PlantClassifier` с адаптером `OnnxPlantClassifier` (MobileNetV2 ONNX, ImageNet) и детерминированным адаптером для тестов, расширение контракта `media.api` методом `loadContent`, ADR-009, docs, приёмочный `ModerationApiIT` и inference-демо по флагу `-P inference`.

**Architecture:** Модульный монолит, bounded context `moderation` (api-нет/domain/application/adapter) — downstream от plants и media. Подписка на `PlantSubmitted` — in-process Spring-событие, слушатель в `moderation.adapter.in.events` (ACL-место по context map). Решение передаётся командой `plants.api.PlantModeration.recordDecision` через ACL `moderation.adapter.out.plants`. Байты изображения — `media.api.MediaAssets.loadContent` через ACL `moderation.adapter.out.media` (storageKey не пересекает границу media). Классификация — порт `moderation.application.port.out.PlantClassifier`; основная реализация `OnnxPlantClassifier` (ONNX Runtime внутри монолита, ADR-009), в тестах — `DeterministicPlantClassifier` через `@TestConfiguration`. Транзакции по разделу 12: короткая tx захвата задания, инференс вне tx, короткая tx применения (сначала `recordDecision`, затем job DONE); конфликт решения → job DONE/STALE; техническая ошибка → RETRY, растение остаётся PENDING.

**Tech Stack:** Java 21, Spring Boot 4.0.8 (MVC, Data JPA, @EnableScheduling), PostgreSQL 17.5 + Flyway (по контекстам, ADR-003), ONNX Runtime 1.20.0 (`com.microsoft.onnxruntime:onnxruntime`), maven-download-plugin 1.13.0 (модель ~13 МБ), Awaitility (test, версия из BOM Boot), Testcontainers 2.0.5, ArchUnit 1.5.0, JaCoCo 0.8.15.

## Global Constraints

- Ветка `feat/iteration-4-moderation` (от `main`); Conventional Commits; тесты коммитируются вместе с реализацией или раньше; красные тесты в `main` не попадают.
- TDD (раздел 14): сначала красный приёмочный `ModerationApiIT` (Task 1), затем внутренний цикл red→green→refactor (домен → application → хранилище → адаптеры).
- `./mvnw verify` — единственная команда проверки; совокупный LINE coverage ≥ 70% (gate). Inference-IT в обычный `verify` не входит (тег `inference` исключён из failsafe; запуск `-P inference`).
- Все ID — UUID; время — `Instant`/UTC из внедрённого `Clock`; домен получает `Instant now` аргументом, не `Instant.now()`.
- Enum — VARCHAR + CHECK в миграциях, JPA `EnumType.STRING` не используется (маппинг явный, `.name()`); Hibernate `ddl-auto=validate`; Flyway — единственный источник структуры.
- `version` (optimistic locking, JPA `@Version`) — у агрегата `ModerationJob` (конкурентный захват due-задания ловит БД).
- Границы контекстов — только через `api` и только из адаптеров (ArchUnit). `moderation.application` не импортирует plants/media вообще; ACL живут в `moderation.adapter.out.{plants,media}` и `moderation.adapter.in.events`. Правило `ContextBoundaryTest.только_config_и_acl_адаптеры_знают_несколько_контекстов` расширяется признанием `adapter.in.events` ACL-местом (соответствует уже записанному правилу context-map.md; не ослабление).
- Отступление «одна транзакция — один агрегат» (раздел 12): применение результата = `PlantModeration.recordDecision` + job DONE в одной tx — описать в ADR-009 с планом на лабу №2.
- **Урок итерации 2:** абстрактные базовые классы контрактных тестов репозиториев обязаны нести `@Transactional` на себе.
- Новые понятия — сначала в `docs/domain/glossary.md` (строки уже есть для `ModerationJob`; добавить `PlantClassifier`, «Инференс»), изменения правил — сначала в docs (context map, aggregates, ADR-009), затем код (Task 7 — как в итерациях 2–3: docs-коммит замыкает итерацию).
- Модель MobileNetV2 скачивается при сборке в `target/models/mobilenetv2-1.0.onnx` (maven-download-plugin, `failOnError=false` — offline-сборка не ломается). Отсутствие модели = честная незавершённость: `ClassifierUnavailableException` при `classify()`, job RETRY, plant PENDING.
- Среда: macOS/zsh; рабочая директория `lab1-monolith/`; scratch-файлы диагностики не коммитировать; Python 3.9.6 (без `X | None`).

## Карта файлов итерации

```text
src/main/java/com/plantarena/
├── media/
│   ├── api/MediaContent.java                        НОВОЕ: record(content, mimeType)
│   ├── api/MediaAssets.java                         ИЗМЕНЕНО: +loadContent(assetId)
│   └── application/MediaAssetsFacade.java           ИЗМЕНЕНО: +loadContent (FileStorage.read)
├── moderation/
│   ├── domain/
│   │   ├── ModerationJob.java                       НОВОЕ: агрегат (claim/succeed/retry/completeStale, backoff)
│   │   ├── ModerationJobStatus.java                 НОВОЕ: NEW/IN_PROGRESS/RETRY/DONE
│   │   ├── ModerationReasonCode.java                НОВОЕ: PLANT_DETECTED/NOT_A_PLANT/STALE
│   │   └── ModerationJobRepository.java             НОВОЕ: порт (save/findById/findDue/findLatestByPlantId)
│   ├── application/
│   │   ├── port/in/CreateModerationJobUseCase.java  НОВОЕ
│   │   ├── port/in/ProcessDueModerationJobsUseCase.java НОВОЕ
│   │   ├── port/out/PlantClassifier.java            НОВОЕ: порт + record Classification
│   │   ├── port/out/ClassifierUnavailableException.java  НОВОЕ: техническая ошибка → retry
│   │   ├── port/out/MediaContentGateway.java        НОВОЕ: ACL-порт байтов файла
│   │   ├── port/out/PlantModerationGateway.java     НОВОЕ: ACL-порт решения
│   │   ├── DecisionConflictException.java           НОВОЕ: конфликт решения → DONE/STALE
│   │   ├── CreateModerationJobService.java          НОВОЕ: событие → job NEW (идемпотентно)
│   │   ├── ProcessModerationJobsService.java        НОВОЕ: poller-use-case, tx-структура раздела 12
│   │   └── ApplyModerationResultService.java        НОВОЕ: одна tx: recordDecision → job DONE
│   └── adapter/
│       ├── in/events/PlantSubmittedHandler.java     НОВОЕ: @EventListener(PlantSubmittedEvent)
│       ├── in/jobs/ModerationJobPoller.java         НОВОЕ: @Scheduled(fixedDelay=2s), пачка 10
│       ├── out/media/InProcessMediaContentGateway.java  НОВОЕ: ACL media.api.loadContent
│       ├── out/plants/InProcessPlantModerationGateway.java НОВОЕ: ACL plants.api.PlantModeration
│       └── out/classifier/OnnxPlantClassifier.java  НОВОЕ: ONNX Runtime, MobileNetV2 (ADR-009)
├── config/ModerationWiringConfig.java               НОВОЕ: @EnableScheduling + бин OnnxPlantClassifier
└── resources/
    ├── application.yml                              ИЗМЕНЕНО: plantarena.moderation.model-path
    └── db/migration/moderation/V2__moderation_jobs.sql  НОВОЕ (V1__init.sql — placeholder SELECT 1)

pom.xml                                              ИЗМЕНЕНО: onnxruntime, awaitility(test),
                                                     download-maven-plugin, failsafe excludedGroups,
                                                     profile inference
src/test/java/com/plantarena/
├── media/application/MediaAssetsFacadeTest.java     НОВОЕ: loadContent (байты/mimeType; storageKey не покидает media)
├── moderation/
│   ├── ModerationApiIT.java                         НОВОЕ (Task 1, красный): сквозной сценарий HTTP
│   ├── domain/ModerationJobTest.java                НОВОЕ: переходы, инварианты, backoff-кап
│   ├── application/
│   │   ├── CreateModerationJobServiceTest.java      НОВОЕ
│   │   ├── ProcessModerationJobsTest.java           НОВОЕ: SUCCESS/ошибка/конфликт/due-фильтр
│   │   └── support/                                 НОВОЕ: InMemoryModerationJobRepository, фейки портов
│   ├── ModerationJobRepositoryContractTest.java     НОВОЕ: абстрактный контракт
│   ├── application/support/InMemoryModerationJobRepositoryContractTest.java НОВОЕ
│   ├── adapter/out/persistence/JpaModerationJobRepositoryContractIT.java    НОВОЕ (Testcontainers)
│   ├── adapter/out/classifier/OnnxPlantClassifierIT.java  НОВОЕ: @Tag("inference"), эталонные фото
│   └── support/DeterministicPlantClassifier.java    НОВОЕ: зелёное изображение → растение
├── architecture/ContextBoundaryTest.java            ИЗМЕНЕНО: adapter.in.events — ACL-место
src/test/resources/moderation/reference/
├── daisy.jpg                                        НОВОЕ: фото ромашки (Wikimedia Commons, ~45 КБ)
└── dog.jpg                                          НОВОЕ: фото собаки (Wikimedia Commons, ~23 КБ)

docs/domain/adr/ADR-009-plant-moderation-onnx.md     НОВОЕ (Task 7)
docs/domain/{glossary,aggregates,context-map}.md, README.md  ИЗМЕНЕНО (Task 7)
```

---

### Task 1: media.api loadContent, порт PlantClassifier, детерминированный классификатор, красный ModerationApiIT

**Files:**
- Create: `src/main/java/com/plantarena/media/api/MediaContent.java`
- Modify: `src/main/java/com/plantarena/media/api/MediaAssets.java`
- Modify: `src/main/java/com/plantarena/media/application/MediaAssetsFacade.java`
- Create: `src/main/java/com/plantarena/moderation/application/port/out/PlantClassifier.java`
- Create: `src/main/java/com/plantarena/moderation/application/port/out/ClassifierUnavailableException.java`
- Create: `src/test/java/com/plantarena/moderation/support/DeterministicPlantClassifier.java`
- Create: `src/test/java/com/plantarena/moderation/support/DeterministicPlantClassifierTest.java`
- Create: `src/test/java/com/plantarena/media/application/MediaAssetsFacadeTest.java`
- Create: `src/test/java/com/plantarena/moderation/ModerationApiIT.java`
- Modify: `pom.xml` (awaitility, test-scope)

**Interfaces:**
- Consumes: `MediaAssetRepository.findById` (существует), `FileStorage.read(storageKey)` (существует), `MediaAsset.storageKey()/format().mimeType()` (существуют), REST итераций 2–3 (`/api/v1/files`, `/api/v1/plants`, `/api/v1/users`), `AbstractIntegrationTest`.
- Produces (для Tasks 2–6): `MediaAssets.loadContent(UUID) → Optional<MediaContent>`, `record MediaContent(byte[] content, String mimeType)`; порт `PlantClassifier.classify(byte[]) → Classification`, `record Classification(boolean plant, float confidence, String modelVersion)`, `ClassifierUnavailableException extends RuntimeException`; тестовый `DeterministicPlantClassifier.MODEL_VERSION = "deterministic-green-v1"`; красный `ModerationApiIT` (зелёный в Task 6).

- [ ] **Step 1: awaitility в pom.xml**

В `<dependencies>` после `spring-boot-jdbc-test` (версия управляется BOM Boot):

```xml
    <dependency>
      <groupId>org.awaitility</groupId>
      <artifactId>awaitility</artifactId>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 2: Контракт media.api — loadContent**

`src/main/java/com/plantarena/media/api/MediaContent.java`:

```java
package com.plantarena.media.api;

/**
 * DTO опубликованного контракта: байты файла и MIME-тип. storageKey не
 * раскрывается — чтение из FileStorage остаётся внутри media (раздел 4.3).
 */
public record MediaContent(byte[] content, String mimeType) {
}
```

`src/main/java/com/plantarena/media/api/MediaAssets.java` — добавить метод (mimeType, а не доменный ImageFormat: api не зависит от domain, LayerRules):

```java
public interface MediaAssets {

    Optional<MediaAssetData> findById(UUID assetId);

    /** Байты файла по id для внутреннего потребителя (moderation, ADR-009). */
    Optional<MediaContent> loadContent(UUID assetId);
}
```

- [ ] **Step 3: Красный тест фасада**

`src/test/java/com/plantarena/media/application/MediaAssetsFacadeTest.java`:

```java
package com.plantarena.media.application;

import com.plantarena.media.api.MediaContent;
import com.plantarena.media.application.support.InMemoryFileStorage;
import com.plantarena.media.application.support.InMemoryMediaAssetRepository;
import com.plantarena.media.domain.ImageFingerprint;
import com.plantarena.media.domain.ImageFormat;
import com.plantarena.media.domain.MediaAsset;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Контракт MediaAssets.loadContent: байты и mimeType, storageKey не покидает media")
class MediaAssetsFacadeTest {

    private final InMemoryMediaAssetRepository repository = new InMemoryMediaAssetRepository();
    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final MediaAssetsFacade facade = new MediaAssetsFacade(repository, storage);

    private UUID storedAsset(byte[] content, String mimeType) {
        ImageFormat format = ImageFormat.fromMimeType(mimeType);
        String storageKey = storage.save(content, format);
        MediaAsset asset = MediaAsset.uploaded(UUID.randomUUID(), storageKey, format,
            content.length, 8, 8, "sha256",
            new ImageFingerprint("f".repeat(64), 1), Instant.parse("2026-09-27T10:00:00Z"));
        repository.save(asset);
        return asset.id();
    }

    @Test
    @DisplayName("loadContent возвращает сохранённые байты и mimeType")
    void loadContent_возвращает_байты_и_mime_тип() {
        byte[] content = new byte[] {1, 2, 3};
        UUID assetId = storedAsset(content, "image/png");

        MediaContent loaded = facade.loadContent(assetId).orElseThrow();

        assertThat(loaded.content()).containsExactly(content);
        assertThat(loaded.mimeType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("неизвестный id — пустой результат")
    void loadContent_неизвестный_id_пустой_результат() {
        assertThat(facade.loadContent(UUID.randomUUID())).isEmpty();
    }
}
```

Проверить перед реализацией: `ImageFormat.fromMimeType` и константы `PNG`/`JPEG` существуют (проверено при планировании); `MediaAsset.uploaded(ownerId, storageKey, format, byteSize, width, height, rawSha256, fingerprint, createdAt)` — сигнатура итерации 2.

- [ ] **Step 4: Запустить — красный**

```bash
./mvnw -q test -Dtest=MediaAssetsFacadeTest
```

Ожидание: FAIL (компиляция): `cannot find symbol: method loadContent` в фасаде (метод в интерфейсе уже объявлен, реализации нет — Spring не запускается, это ошибка компиляции абстрактного класса `MediaAssetsFacade`).

- [ ] **Step 5: Реализация фасада**

`src/main/java/com/plantarena/media/application/MediaAssetsFacade.java` — заменить целиком:

```java
package com.plantarena.media.application;

import com.plantarena.media.api.MediaAssetData;
import com.plantarena.media.api.MediaAssets;
import com.plantarena.media.api.MediaContent;
import com.plantarena.media.application.port.out.MediaAssetRepository;
import com.plantarena.media.domain.FileStorage;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация опубликованного контракта MediaAssets: метаданные и байты без
 * внутренних деталей (storageKey остаётся в media). loadContent — для
 * внутреннего контракта монолита (moderation, ADR-009): права не проверяются,
 * вызов идёт по assetId из задания модерации.
 */
@Service
@Transactional(readOnly = true)
public class MediaAssetsFacade implements MediaAssets {

    private final MediaAssetRepository repository;
    private final FileStorage fileStorage;

    public MediaAssetsFacade(MediaAssetRepository repository, FileStorage fileStorage) {
        this.repository = repository;
        this.fileStorage = fileStorage;
    }

    @Override
    public Optional<MediaAssetData> findById(UUID assetId) {
        return repository.findById(assetId)
            .map(asset -> new MediaAssetData(asset.id(), asset.ownerId(),
                asset.fingerprint().value(), asset.fingerprint().version()));
    }

    @Override
    public Optional<MediaContent> loadContent(UUID assetId) {
        return repository.findById(assetId)
            .map(asset -> new MediaContent(fileStorage.read(asset.storageKey()),
                asset.format().mimeType()));
    }
}
```

- [ ] **Step 6: Запустить — зелёный**

```bash
./mvnw -q test -Dtest=MediaAssetsFacadeTest
```

Ожидание: PASS (2 теста). Затем `./mvnw -q verify -Dit.test=MediaApiIT -DfailIfNoTests=false` — существующие media-IT не сломаны (конструктор фасада расширился, Spring соберёт сам: `LocalFileStorage` — бин).

- [ ] **Step 7: Порт PlantClassifier + исключение**

`src/main/java/com/plantarena/moderation/application/port/out/PlantClassifier.java`:

```java
package com.plantarena.moderation.application.port.out;

/**
 * Порт классификатора растений (ADR-009): байты изображения → решение.
 * Потребитель (moderation) владеет типами; реализации — адаптеры
 * (OnnxPlantClassifier в основном профиле, DeterministicPlantClassifier в тестах).
 */
public interface PlantClassifier {

    Classification classify(byte[] imageBytes);

    /**
     * @param plant распознано растение (top-1 ∈ растительные классы, confidence ≥ порога)
     * @param confidence уверенность top-1 после softmax, [0..1]
     * @param modelVersion версия модели + списка классов (наблюдаемость)
     */
    record Classification(boolean plant, float confidence, String modelVersion) {
    }
}
```

`src/main/java/com/plantarena/moderation/application/port/out/ClassifierUnavailableException.java`:

```java
package com.plantarena.moderation.application.port.out;

/**
 * Техническая недоступность классификатора (нет модели, сбой инференса,
 * недекодируемый файл) — НЕ решение: задание уходит в RETRY, растение
 * остаётся PENDING (раздел 6, ADR-009).
 */
public class ClassifierUnavailableException extends RuntimeException {

    public ClassifierUnavailableException(String message) {
        super(message);
    }

    public ClassifierUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 8: Детерминированный классификатор (только test) + тест**

`src/test/java/com/plantarena/moderation/support/DeterministicPlantClassifier.java`:

```java
package com.plantarena.moderation.support;

import com.plantarena.moderation.application.port.out.ClassifierUnavailableException;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Детерминированный классификатор для тестов/демо (без сети и модели):
 * изображение с доминирующим зелёным каналом — растение. Используется в
 * ModerationApiIT через @TestConfiguration (спека итерации 4).
 */
public class DeterministicPlantClassifier implements PlantClassifier {

    public static final String MODEL_VERSION = "deterministic-green-v1";

    @Override
    public Classification classify(byte[] imageBytes) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException e) {
            throw new ClassifierUnavailableException("Не удалось декодировать изображение", e);
        }
        if (image == null) {
            throw new ClassifierUnavailableException("Не удалось декодировать изображение");
        }
        long red = 0;
        long green = 0;
        long blue = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                red += (rgb >> 16) & 0xFF;
                green += (rgb >> 8) & 0xFF;
                blue += rgb & 0xFF;
            }
        }
        long pixels = (long) image.getWidth() * image.getHeight();
        boolean plant = green > red && green > blue;
        return new Classification(plant, 0.9f, MODEL_VERSION);
    }
}
```

`src/test/java/com/plantarena/moderation/support/DeterministicPlantClassifierTest.java`:

```java
package com.plantarena.moderation.support;

import com.plantarena.moderation.application.port.out.ClassifierUnavailableException;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Детерминированный классификатор: зелёное — растение, иное — нет")
class DeterministicPlantClassifierTest {

    private final DeterministicPlantClassifier classifier = new DeterministicPlantClassifier();

    @Test
    @DisplayName("зелёное изображение — растение")
    void зелёное_изображение_растение() throws Exception {
        PlantClassifier.Classification result = classifier.classify(image(Color.GREEN));

        assertThat(result.plant()).isTrue();
        assertThat(result.confidence()).isEqualTo(0.9f);
        assertThat(result.modelVersion()).isEqualTo(DeterministicPlantClassifier.MODEL_VERSION);
    }

    @Test
    @DisplayName("красное изображение — не растение")
    void красное_изображение_не_растение() throws Exception {
        assertThat(classifier.classify(image(Color.RED)).plant()).isFalse();
    }

    @Test
    @DisplayName("недекодируемые байты — техническая ошибка (не решение)")
    void недекодируемые_байты_техническая_ошибка() {
        assertThatThrownBy(() -> classifier.classify("не изображение".getBytes()))
            .isInstanceOf(ClassifierUnavailableException.class);
    }

    private byte[] image(Color color) throws IOException {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, 8, 8);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
```

```bash
./mvnw -q test -Dtest='DeterministicPlantClassifierTest'
```

Ожидание: PASS (3 теста).

- [ ] **Step 9: Красный приёмочный ModerationApiIT**

`src/test/java/com/plantarena/moderation/ModerationApiIT.java`:

```java
package com.plantarena.moderation;

import com.jayway.jsonpath.JsonPath;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import com.plantarena.moderation.support.DeterministicPlantClassifier;
import com.plantarena.support.AbstractIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Внешний цикл TDD итерации 4: сквозной сценарий «загрузка → заявка → задание
 * → решение» через HTTP на Testcontainers (раздел 6). Классификатор
 * детерминированный (@TestConfiguration): зелёный PNG → APPROVED, красный →
 * REJECTED. Обработку выполняет @Scheduled-poller (2с) — ждём Awaitility.
 * Красный до Task 6 (задания никто не создаёт и не обрабатывает).
 */
@DisplayName("Сценарии раздела 6 (модерация): заявка → задание → решение (moderation)")
class ModerationApiIT extends AbstractIntegrationTest {

    private static final String DEMO_HEADER = "X-Demo-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Детерминированный классификатор вместо ONNX (спека итерации 4). */
    @TestConfiguration
    static class DeterministicClassifierConfig {

        @Bean
        @Primary
        PlantClassifier deterministicPlantClassifier() {
            return new DeterministicPlantClassifier();
        }
    }

    @Test
    @DisplayName("зелёное изображение одобрено: причина, файл публично виден чужим")
    void зелёное_изображение_одобрено_файл_публичен_чужим() throws Exception {
        UUID ownerId = createUserAsAdmin("mod-green@example.com", "Mod Green");
        UUID strangerId = createUserAsAdmin("mod-green-stranger@example.com", "Mod Green Stranger");
        UUID assetId = uploadAs(ownerId, referenceBytes("green-8x8.png"));
        UUID plantId = submitPlant(ownerId, assetId, "Мой фикус");

        awaitModeration(ownerId, plantId, "APPROVED");

        mockMvc.perform(get("/api/v1/plants/" + plantId + "/moderation")
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.moderationStatus").value("APPROVED"))
            .andExpect(jsonPath("$.reason").value("PLANT_DETECTED"))
            .andExpect(jsonPath("$.retryUploadAllowed").value(false));

        // одобренное растение и его файл публичны (ADR-008)
        mockMvc.perform(get("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, strangerId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.moderationStatus").value("APPROVED"));
        mockMvc.perform(get("/api/v1/files/" + assetId)
                .header(DEMO_HEADER, strangerId.toString()))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("не-растение отклонено: причина NOT_A_PLANT, разрешён повторный upload")
    void не_растение_отклонено_причина_и_повторный_upload() throws Exception {
        UUID ownerId = createUserAsAdmin("mod-red@example.com", "Mod Red");
        UUID strangerId = createUserAsAdmin("mod-red-stranger@example.com", "Mod Red Stranger");
        UUID assetId = uploadAs(ownerId, referenceBytes("red-8x8.png"));
        UUID plantId = submitPlant(ownerId, assetId, "Кот в горшке");

        awaitModeration(ownerId, plantId, "REJECTED");

        mockMvc.perform(get("/api/v1/plants/" + plantId + "/moderation")
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.moderationStatus").value("REJECTED"))
            .andExpect(jsonPath("$.reason").value("NOT_A_PLANT"))
            .andExpect(jsonPath("$.retryUploadAllowed").value(true));

        // отклонённый файл не публичен: чужой получает 404 (ADR-008)
        mockMvc.perform(get("/api/v1/files/" + assetId)
                .header(DEMO_HEADER, strangerId.toString()))
            .andExpect(status().isNotFound());
    }

    /** Ждём решения poller'а (fixedDelay 2с): PENDING → APPROVED/REJECTED. */
    private void awaitModeration(UUID ownerId, UUID plantId, String expectedStatus) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> mockMvc.perform(
                    get("/api/v1/plants/" + plantId + "/moderation")
                        .header(DEMO_HEADER, ownerId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value(expectedStatus)));
    }

    private UUID submitPlant(UUID ownerId, UUID assetId, String title) throws Exception {
        String response = mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":\"%s\",\"title\":\"%s\"}".formatted(assetId, title)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private UUID uploadAs(UUID userId, byte[] content) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("file", "reference.png",
                    MediaType.IMAGE_PNG_VALUE, content))
                .header(DEMO_HEADER, userId.toString()))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private byte[] referenceBytes(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/media/reference/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Эталонный файл не найден: " + name);
            }
            return in.readAllBytes();
        }
    }

    private UUID adminId() {
        return jdbcTemplate.queryForObject(
            "select id from identity.app_user where email_normalized = ?",
            UUID.class, "admin@plantarena.local");
    }

    private UUID createUserAsAdmin(String email, String displayName) throws Exception {
        String response = mockMvc.perform(post("/api/v1/users")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"password-9","displayName":"%s"}
                    """.formatted(email, displayName)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }
}
```

Внимание: комментарий класса — как в коде выше (без опечаток).

- [ ] **Step 10: Запустить — красный**

```bash
./mvnw -q verify -Dit.test=ModerationApiIT -DfailIfNoTests=false
```

Ожидание: unit-тесты PASS; `ModerationApiIT` FAIL: `ConditionTimeoutException` — статус модерации остаётся `PENDING` (задания не создаются: слушателя нет). Это честный красный внешнего цикла.

- [ ] **Step 11: Commit**

```bash
git checkout -b feat/iteration-4-moderation
git add pom.xml src/main/java/com/plantarena/media src/main/java/com/plantarena/moderation/application/port/out src/test/java/com/plantarena/media/application/MediaAssetsFacadeTest.java src/test/java/com/plantarena/moderation
git commit -m "feat(media): контракт MediaAssets.loadContent — байты для moderation; порт PlantClassifier, красный ModerationApiIT"
```

---

### Task 2: Домен ModerationJob — агрегат с retry-политикой

**Files:**
- Create: `src/main/java/com/plantarena/moderation/domain/ModerationJobStatus.java`
- Create: `src/main/java/com/plantarena/moderation/domain/ModerationReasonCode.java`
- Create: `src/main/java/com/plantarena/moderation/domain/ModerationJob.java`
- Create: `src/main/java/com/plantarena/moderation/domain/ModerationJobRepository.java`
- Test: `src/test/java/com/plantarena/moderation/domain/ModerationJobTest.java`

**Interfaces:**
- Consumes: ничего (чистая Java).
- Produces (для Tasks 3–4): `ModerationJob.create(plantId, assetId, now)`, `claim(now)`, `succeed(modelVersion, confidence, reasonCode, now)`, `retry(now)`, `completeStale(now)`, `restore(...)` (13 аргументов — см. код), геттеры `id()/plantId()/assetId()/status()/attempts()/nextAttemptAt()/modelVersion()/confidence()/reasonCode()/startedAt()/completedAt()/createdAt()/version()`; `static Duration backoff(int attempts)`; enum `ModerationJobStatus {NEW, IN_PROGRESS, RETRY, DONE}`, enum `ModerationReasonCode {PLANT_DETECTED, NOT_A_PLANT, STALE}`; порт `ModerationJobRepository {save, findById, findDue(now, limit), findLatestByPlantId}`.

- [ ] **Step 1: Красный доменный тест**

`src/test/java/com/plantarena/moderation/domain/ModerationJobTest.java`:

```java
package com.plantarena.moderation.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Агрегат ModerationJob: переходы, инварианты, backoff без лимита с капом")
class ModerationJobTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @Test
    @DisplayName("фабрика: NEW, attempts=0, nextAttemptAt=now, без результата")
    void фабрика_new() {
        ModerationJob job = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);

        assertThat(job.status()).isEqualTo(ModerationJobStatus.NEW);
        assertThat(job.attempts()).isZero();
        assertThat(job.nextAttemptAt()).isEqualTo(NOW);
        assertThat(job.startedAt()).isNull();
        assertThat(job.completedAt()).isNull();
        assertThat(job.modelVersion()).isNull();
        assertThat(job.confidence()).isNull();
        assertThat(job.reasonCode()).isNull();
        assertThat(job.createdAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("claim: NEW → IN_PROGRESS, attempts++, startedAt первой попытки")
    void claim_из_new() {
        ModerationJob job = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);

        job.claim(NOW.plusSeconds(5));

        assertThat(job.status()).isEqualTo(ModerationJobStatus.IN_PROGRESS);
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(job.startedAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    @DisplayName("claim: RETRY → IN_PROGRESS (повторная попытка), startedAt не перезаписывается")
    void claim_из_retry() {
        ModerationJob job = claimedThenRetried();

        job.claim(NOW.plusSeconds(10));

        assertThat(job.status()).isEqualTo(ModerationJobStatus.IN_PROGRESS);
        assertThat(job.attempts()).isEqualTo(2);
        assertThat(job.startedAt()).isEqualTo(NOW); // первая попытка
    }

    @Test
    @DisplayName("claim из DONE невозможен: DONE терминален")
    void claim_из_done_невозможен() {
        ModerationJob job = succeeded();

        assertThatThrownBy(() -> job.claim(NOW.plusSeconds(60)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("succeed: IN_PROGRESS → DONE, результат обязателен")
    void succeed_заполняет_результат() {
        ModerationJob job = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        job.claim(NOW);

        job.succeed("mobilenetv2-1.0-onnx-imagenet/plant-classes-v1", 0.87f,
            ModerationReasonCode.PLANT_DETECTED, NOW.plusSeconds(1));

        assertThat(job.status()).isEqualTo(ModerationJobStatus.DONE);
        assertThat(job.modelVersion()).isEqualTo("mobilenetv2-1.0-onnx-imagenet/plant-classes-v1");
        assertThat(job.confidence()).isEqualTo(0.87f);
        assertThat(job.reasonCode()).isEqualTo(ModerationReasonCode.PLANT_DETECTED);
        assertThat(job.completedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    @DisplayName("succeed: только из IN_PROGRESS; confidence ∈ [0..1]; STALE запрещён")
    void succeed_инварианты() {
        ModerationJob newJob = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        assertThatThrownBy(() -> newJob.succeed("m", 0.5f,
            ModerationReasonCode.PLANT_DETECTED, NOW))
            .isInstanceOf(IllegalStateException.class);

        ModerationJob claimed = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        claimed.claim(NOW);
        assertThatThrownBy(() -> claimed.succeed("m", 1.5f,
            ModerationReasonCode.PLANT_DETECTED, NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claimed.succeed("m", 0.5f, ModerationReasonCode.STALE, NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claimed.succeed(" ", 0.5f,
            ModerationReasonCode.PLANT_DETECTED, NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("retry: IN_PROGRESS → RETRY, backoff 1с → 2с → 4с … кап 1ч")
    void retry_backoff() {
        ModerationJob job = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        job.claim(NOW);
        job.retry(NOW);
        assertThat(job.status()).isEqualTo(ModerationJobStatus.RETRY);
        assertThat(job.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofSeconds(1)));

        job.claim(NOW.plusSeconds(1));
        job.retry(NOW.plusSeconds(1));
        assertThat(job.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofSeconds(3)));

        job.claim(NOW.plusSeconds(3));
        job.retry(NOW.plusSeconds(3));
        assertThat(job.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofSeconds(7)));
    }

    @Test
    @DisplayName("backoff: экспонента с капом 1ч, без переполнения")
    void backoff_кап() {
        assertThat(ModerationJob.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(ModerationJob.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(ModerationJob.backoff(12)).isEqualTo(Duration.ofSeconds(2048));
        assertThat(ModerationJob.backoff(13)).isEqualTo(Duration.ofHours(1));
        assertThat(ModerationJob.backoff(100)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("retry из NEW/DONE невозможен")
    void retry_только_из_in_progress() {
        ModerationJob newJob = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        assertThatThrownBy(() -> newJob.retry(NOW))
            .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> succeeded().retry(NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("completeStale: IN_PROGRESS → DONE с reasonCode=STALE (устаревший результат)")
    void completeStale() {
        ModerationJob job = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        job.claim(NOW);

        job.completeStale(NOW.plusSeconds(2));

        assertThat(job.status()).isEqualTo(ModerationJobStatus.DONE);
        assertThat(job.reasonCode()).isEqualTo(ModerationReasonCode.STALE);
        assertThat(job.completedAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(job.modelVersion()).isNull();
    }

    private ModerationJob claimedThenRetried() {
        ModerationJob job = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        job.claim(NOW);
        job.retry(NOW);
        return job;
    }

    private ModerationJob succeeded() {
        ModerationJob job = ModerationJob.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        job.claim(NOW);
        job.succeed("m", 0.9f, ModerationReasonCode.PLANT_DETECTED, NOW);
        return job;
    }
}
```

- [ ] **Step 2: Запустить — красный**

```bash
./mvnw -q test -Dtest=ModerationJobTest
```

Ожидание: FAIL (компиляция): `cannot find symbol: class ModerationJob`.

- [ ] **Step 3: Реализация домена**

`src/main/java/com/plantarena/moderation/domain/ModerationJobStatus.java`:

```java
package com.plantarena.moderation.domain;

/** Статусы задания модерации: NEW → IN_PROGRESS → DONE | RETRY; RETRY → IN_PROGRESS. */
public enum ModerationJobStatus {
    NEW, IN_PROGRESS, RETRY, DONE
}
```

`src/main/java/com/plantarena/moderation/domain/ModerationReasonCode.java`:

```java
package com.plantarena.moderation.domain;

/** Причина завершения задания; заполняется только при DONE (раздел 6). */
public enum ModerationReasonCode {
    PLANT_DETECTED, NOT_A_PLANT, STALE
}
```

`src/main/java/com/plantarena/moderation/domain/ModerationJob.java`:

```java
package com.plantarena.moderation.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат moderation (раздел 6): задание автоматического распознавания по
 * заявке. Инварианты: DONE терминален; переходы только NEW/RETRY →
 * IN_PROGRESS → DONE | RETRY; результат (modelVersion/confidence/reasonCode)
 * заполняется только при DONE; ошибка распознавателя — НЕ решение (RETRY,
 * растение остаётся PENDING). Retry без лимита: attempts — наблюдаемость;
 * backoff экспоненциальный с капом 1ч (ADR-009). Время приходит аргументом.
 */
public final class ModerationJob {

    private static final Duration BACKOFF_CAP = Duration.ofHours(1);

    private final UUID id;
    private final UUID plantId;
    private final UUID assetId;
    private ModerationJobStatus status;
    private int attempts;
    private Instant nextAttemptAt;
    private String modelVersion;
    private Float confidence;
    private ModerationReasonCode reasonCode;
    private Instant startedAt;
    private Instant completedAt;
    private final Instant createdAt;
    private long version;

    private ModerationJob(UUID id, UUID plantId, UUID assetId, ModerationJobStatus status,
                          int attempts, Instant nextAttemptAt, String modelVersion,
                          Float confidence, ModerationReasonCode reasonCode, Instant startedAt,
                          Instant completedAt, Instant createdAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.plantId = Objects.requireNonNull(plantId, "plantId");
        this.assetId = Objects.requireNonNull(assetId, "assetId");
        this.status = Objects.requireNonNull(status, "status");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts не может быть отрицательным");
        }
        this.attempts = attempts;
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        this.modelVersion = modelVersion;
        this.confidence = confidence;
        this.reasonCode = reasonCode;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.version = version;
    }

    /** Новое задание по заявке: NEW, готово к захвату немедленно. */
    public static ModerationJob create(UUID plantId, UUID assetId, Instant now) {
        return new ModerationJob(UUID.randomUUID(), plantId, assetId, ModerationJobStatus.NEW,
            0, now, null, null, null, null, null, now, 0);
    }

    /** Восстановление из хранилища с сохранением id и version (JPA-адаптер). */
    public static ModerationJob restore(UUID id, UUID plantId, UUID assetId,
                                        ModerationJobStatus status, int attempts,
                                        Instant nextAttemptAt, String modelVersion,
                                        Float confidence, ModerationReasonCode reasonCode,
                                        Instant startedAt, Instant completedAt,
                                        Instant createdAt, long version) {
        return new ModerationJob(id, plantId, assetId, status, attempts, nextAttemptAt,
            modelVersion, confidence, reasonCode, startedAt, completedAt, createdAt, version);
    }

    /** Захват due-задания воркером: attempts++, startedAt фиксируется первой попыткой. */
    public void claim(Instant now) {
        requireStatus(ModerationJobStatus.NEW, ModerationJobStatus.RETRY);
        status = ModerationJobStatus.IN_PROGRESS;
        attempts++;
        if (startedAt == null) {
            startedAt = now;
        }
    }

    /** Успешная попытка: результат обязателен, STALE здесь запрещён (отдельная команда). */
    public void succeed(String modelVersion, float confidence, ModerationReasonCode reasonCode,
                        Instant now) {
        requireStatus(ModerationJobStatus.IN_PROGRESS);
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion обязательна");
        }
        if (confidence < 0f || confidence > 1f) {
            throw new IllegalArgumentException("confidence должна быть в [0..1]");
        }
        if (reasonCode == null || reasonCode == ModerationReasonCode.STALE) {
            throw new IllegalArgumentException("reasonCode успешной попытки — PLANT_DETECTED/NOT_A_PLANT");
        }
        status = ModerationJobStatus.DONE;
        this.modelVersion = modelVersion;
        this.confidence = confidence;
        this.reasonCode = reasonCode;
        completedAt = now;
    }

    /** Техническая ошибка — НЕ решение: RETRY с экспоненциальным backoff (без лимита попыток). */
    public void retry(Instant now) {
        requireStatus(ModerationJobStatus.IN_PROGRESS);
        status = ModerationJobStatus.RETRY;
        nextAttemptAt = now.plus(backoff(attempts));
    }

    /** Устаревший результат: решение по заявке уже есть, повтор не применяется. */
    public void completeStale(Instant now) {
        requireStatus(ModerationJobStatus.IN_PROGRESS);
        status = ModerationJobStatus.DONE;
        reasonCode = ModerationReasonCode.STALE;
        completedAt = now;
    }

    /** Backoff попытки N: 1с, 2с, 4с, … кап 1ч (раздел 6, ADR-009). */
    static Duration backoff(int attempts) {
        if (attempts >= 13) {
            return BACKOFF_CAP;
        }
        return Duration.ofSeconds(1L << (attempts - 1));
    }

    private void requireStatus(ModerationJobStatus... allowed) {
        for (ModerationJobStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException(
            "Недопустимый переход из статуса " + status + " (ожидается "
                + String.join("/", java.util.Arrays.stream(allowed)
                    .map(Enum::name).toList()) + ")");
    }

    public UUID id() {
        return id;
    }

    public UUID plantId() {
        return plantId;
    }

    public UUID assetId() {
        return assetId;
    }

    public ModerationJobStatus status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public String modelVersion() {
        return modelVersion;
    }

    public Float confidence() {
        return confidence;
    }

    public ModerationReasonCode reasonCode() {
        return reasonCode;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }
}
```

`src/main/java/com/plantarena/moderation/domain/ModerationJobRepository.java`:

```java
package com.plantarena.moderation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Порт хранилища заданий модерации. findDue — NEW/RETRY с nextAttemptAt <= now,
 * по возрастанию nextAttemptAt (детерминированный порядок, tie-break по id).
 */
public interface ModerationJobRepository {

    ModerationJob save(ModerationJob job);

    Optional<ModerationJob> findById(UUID id);

    List<ModerationJob> findDue(Instant now, int limit);

    Optional<ModerationJob> findLatestByPlantId(UUID plantId);
}
```

- [ ] **Step 4: Запустить — зелёный**

```bash
./mvnw -q test -Dtest=ModerationJobTest
```

Ожидание: PASS (10 тестов).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/plantarena/moderation/domain src/test/java/com/plantarena/moderation/domain
git commit -m "feat(moderation): агрегат ModerationJob — переходы, retry без лимита, backoff-кап 1ч"
```

---

### Task 3: application — порты, use cases, tx-структура применения результата

**Files:**
- Create: `src/main/java/com/plantarena/moderation/application/port/in/CreateModerationJobUseCase.java`
- Create: `src/main/java/com/plantarena/moderation/application/port/in/ProcessDueModerationJobsUseCase.java`
- Create: `src/main/java/com/plantarena/moderation/application/port/out/MediaContentGateway.java`
- Create: `src/main/java/com/plantarena/moderation/application/port/out/PlantModerationGateway.java`
- Create: `src/main/java/com/plantarena/moderation/application/DecisionConflictException.java`
- Create: `src/main/java/com/plantarena/moderation/application/CreateModerationJobService.java`
- Create: `src/main/java/com/plantarena/moderation/application/ProcessModerationJobsService.java`
- Create: `src/main/java/com/plantarena/moderation/application/ApplyModerationResultService.java`
- Create (test support): `src/test/java/com/plantarena/moderation/application/support/InMemoryModerationJobRepository.java`
- Create (test support): `src/test/java/com/plantarena/moderation/application/support/FakePlantModerationGateway.java`
- Create (test support): `src/test/java/com/plantarena/moderation/application/support/FakeMediaContentGateway.java`
- Create (test support): `src/test/java/com/plantarena/moderation/application/support/ScriptedClassifier.java`
- Test: `src/test/java/com/plantarena/moderation/application/CreateModerationJobServiceTest.java`
- Test: `src/test/java/com/plantarena/moderation/application/ProcessModerationJobsTest.java`

**Interfaces:**
- Consumes: `ModerationJobRepository` (Task 2), `PlantClassifier` (Task 1), `Clock` (бин `ClockConfig`).
- Produces (для Task 6): `CreateModerationJobUseCase.onPlantSubmitted(UUID plantId, UUID assetId)`; `ProcessDueModerationJobsUseCase.processDue(int limit) → int`; `MediaContentGateway.loadContent(UUID) → Optional<MediaContent>` c `record MediaContent(byte[] bytes, String mimeType)`; `PlantModerationGateway.recordDecision(UUID plantId, Decision decision, String reason)` c `enum Decision {APPROVED, REJECTED}`; `DecisionConflictException`.

- [ ] **Step 1: Порты и исключение**

`src/main/java/com/plantarena/moderation/application/port/in/CreateModerationJobUseCase.java`:

```java
package com.plantarena.moderation.application.port.in;

import java.util.UUID;

/** Реакция на PlantSubmitted: создать задание распознавания (идемпотентно). */
public interface CreateModerationJobUseCase {

    void onPlantSubmitted(UUID plantId, UUID assetId);
}
```

`src/main/java/com/plantarena/moderation/application/port/in/ProcessDueModerationJobsUseCase.java`:

```java
package com.plantarena.moderation.application.port.in;

/** Обработать пачку due-заданий (вызывает @Scheduled-poller). */
public interface ProcessDueModerationJobsUseCase {

    int processDue(int limit);
}
```

`src/main/java/com/plantarena/moderation/application/port/out/MediaContentGateway.java`:

```java
package com.plantarena.moderation.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * ACL-порт moderation к байтам файла (media.api.MediaAssets.loadContent).
 * Потребитель владеет типами; storageKey не пересекает границу media.
 */
public interface MediaContentGateway {

    Optional<MediaContent> loadContent(UUID assetId);

    record MediaContent(byte[] bytes, String mimeType) {
    }
}
```

`src/main/java/com/plantarena/moderation/application/port/out/PlantModerationGateway.java`:

```java
package com.plantarena.moderation.application.port.out;

import java.util.UUID;

/**
 * ACL-порт moderation к команде plants.api.PlantModeration.recordDecision.
 * Конфликт решения по уже решённой заявке — DecisionConflictException
 * (задание завершается DONE/STALE, раздел 6).
 */
public interface PlantModerationGateway {

    void recordDecision(UUID plantId, Decision decision, String reason);

    enum Decision {
        APPROVED, REJECTED
    }
}
```

`src/main/java/com/plantarena/moderation/application/DecisionConflictException.java`:

```java
package com.plantarena.moderation.application;

/**
 * Решение по заявке уже зафиксировано (устаревший результат): повтор не
 * применяется, задание завершается DONE с reasonCode=STALE.
 */
public class DecisionConflictException extends RuntimeException {

    public DecisionConflictException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: Тестовые фейки**

`src/test/java/com/plantarena/moderation/application/support/InMemoryModerationJobRepository.java`:

```java
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
```

`src/test/java/com/plantarena/moderation/application/support/FakePlantModerationGateway.java`:

```java
package com.plantarena.moderation.application.support;

import com.plantarena.moderation.application.DecisionConflictException;
import com.plantarena.moderation.application.port.out.PlantModerationGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Фейк команды решения: помнит вызовы; конфликт включается флагом. */
public class FakePlantModerationGateway implements PlantModerationGateway {

    public final List<Recorded> decisions = new ArrayList<>();
    public DecisionConflictException conflict;

    @Override
    public void recordDecision(UUID plantId, Decision decision, String reason) {
        if (conflict != null) {
            throw conflict;
        }
        decisions.add(new Recorded(plantId, decision, reason));
    }

    public record Recorded(UUID plantId, Decision decision, String reason) {
    }
}
```

`src/test/java/com/plantarena/moderation/application/support/FakeMediaContentGateway.java`:

```java
package com.plantarena.moderation.application.support;

import com.plantarena.moderation.application.port.out.MediaContentGateway;
import java.util.Optional;
import java.util.UUID;

/** Фейк байтов файла: preset-байты или «файл исчез». */
public class FakeMediaContentGateway implements MediaContentGateway {

    public byte[] bytes = new byte[] {1, 2, 3};
    public boolean present = true;

    @Override
    public Optional<MediaContent> loadContent(UUID assetId) {
        return present ? Optional.of(new MediaContent(bytes, "image/png")) : Optional.empty();
    }
}
```

`src/test/java/com/plantarena/moderation/application/support/ScriptedClassifier.java`:

```java
package com.plantarena.moderation.application.support;

import com.plantarena.moderation.application.port.out.PlantClassifier;

/** Сценарный классификатор: preset-результат или включаемый сбой. */
public class ScriptedClassifier implements PlantClassifier {

    public Classification result = new Classification(true, 0.9f, "scripted-v1");
    public RuntimeException failure;

    @Override
    public Classification classify(byte[] imageBytes) {
        if (failure != null) {
            throw failure;
        }
        return result;
    }
}
```

- [ ] **Step 3: Красные тесты use cases**

`src/test/java/com/plantarena/moderation/application/CreateModerationJobServiceTest.java`:

```java
package com.plantarena.moderation.application;

import com.plantarena.moderation.application.support.InMemoryModerationJobRepository;
import com.plantarena.moderation.domain.ModerationJob;
import com.plantarena.moderation.domain.ModerationJobStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Событие PlantSubmitted создаёт задание NEW (идемпотентно)")
class CreateModerationJobServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final InMemoryModerationJobRepository jobs = new InMemoryModerationJobRepository();
    private final CreateModerationJobService service =
        new CreateModerationJobService(jobs, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("первое событие — задание NEW, nextAttemptAt=now")
    void событие_создаёт_задание() {
        UUID plantId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();

        service.onPlantSubmitted(plantId, assetId);

        ModerationJob job = jobs.findLatestByPlantId(plantId).orElseThrow();
        assertThat(job.status()).isEqualTo(ModerationJobStatus.NEW);
        assertThat(job.assetId()).isEqualTo(assetId);
        assertThat(job.attempts()).isZero();
        assertThat(job.nextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("повторная доставка события — no-op (одно задание на заявку)")
    void повтор_события_идемпотентен() {
        UUID plantId = UUID.randomUUID();

        service.onPlantSubmitted(plantId, UUID.randomUUID());
        service.onPlantSubmitted(plantId, UUID.randomUUID());

        assertThat(jobs.jobs).hasSize(1);
    }
}
```

`src/test/java/com/plantarena/moderation/application/ProcessModerationJobsTest.java`:

```java
package com.plantarena.moderation.application;

import com.plantarena.moderation.application.port.out.ClassifierUnavailableException;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import com.plantarena.moderation.application.support.FakeMediaContentGateway;
import com.plantarena.moderation.application.support.FakePlantModerationGateway;
import com.plantarena.moderation.application.support.InMemoryModerationJobRepository;
import com.plantarena.moderation.application.support.ScriptedClassifier;
import com.plantarena.moderation.domain.ModerationJob;
import com.plantarena.moderation.domain.ModerationJobStatus;
import com.plantarena.moderation.domain.ModerationReasonCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Обработка due-заданий: успех/ошибка/конфликт, due-фильтр (раздел 12)")
class ProcessModerationJobsTest {

    private final InMemoryModerationJobRepository jobs = new InMemoryModerationJobRepository();
    private final FakeMediaContentGateway media = new FakeMediaContentGateway();
    private final ScriptedClassifier classifier = new ScriptedClassifier();
    private final FakePlantModerationGateway plants = new FakePlantModerationGateway();
    private final SettableClock clock = new SettableClock();

    private final ApplyModerationResultService applyResult =
        new ApplyModerationResultService(jobs, plants, clock);
    private final ProcessModerationJobsService service =
        new ProcessModerationJobsService(jobs, media, classifier, applyResult, clock);

    @Test
    @DisplayName("успех: plant=true → job DONE, решение APPROVED/PLANT_DETECTED, attempts=1")
    void успех_одобрение() {
        UUID plantId = UUID.randomUUID();
        classifier.result = new PlantClassifier.Classification(true, 0.87f, "m-v1");
        submitDueJob(plantId);

        int processed = service.processDue(10);

        assertThat(processed).isEqualTo(1);
        ModerationJob job = jobs.jobs.values().iterator().next();
        assertThat(job.status()).isEqualTo(ModerationJobStatus.DONE);
        assertThat(job.reasonCode()).isEqualTo(ModerationReasonCode.PLANT_DETECTED);
        assertThat(job.modelVersion()).isEqualTo("m-v1");
        assertThat(job.confidence()).isEqualTo(0.87f);
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(plants.decisions).containsExactly(
            new FakePlantModerationGateway.Recorded(plantId,
                com.plantarena.moderation.application.port.out.PlantModerationGateway.Decision.APPROVED,
                "PLANT_DETECTED"));
    }

    @Test
    @DisplayName("успех: plant=false → REJECTED/NOT_A_PLANT")
    void не_растение_отклонение() {
        UUID plantId = UUID.randomUUID();
        classifier.result = new PlantClassifier.Classification(false, 0.99f, "m-v1");
        submitDueJob(plantId);

        service.processDue(10);

        assertThat(plants.decisions).containsExactly(
            new FakePlantModerationGateway.Recorded(plantId,
                com.plantarena.moderation.application.port.out.PlantModerationGateway.Decision.REJECTED,
                "NOT_A_PLANT"));
        assertThat(jobs.jobs.values().iterator().next().reasonCode())
            .isEqualTo(ModerationReasonCode.NOT_A_PLANT);
    }

    @Test
    @DisplayName("техническая ошибка классификатора → RETRY с backoff, решение не применяется")
    void ошибка_классификатора_retry() {
        submitDueJob(UUID.randomUUID());
        classifier.failure = new ClassifierUnavailableException("модель не найдена");

        service.processDue(10);

        ModerationJob job = jobs.jobs.values().iterator().next();
        assertThat(job.status()).isEqualTo(ModerationJobStatus.RETRY);
        assertThat(job.nextAttemptAt()).isEqualTo(clock.instant().plus(Duration.ofSeconds(1)));
        assertThat(plants.decisions).isEmpty();

        // due-фильтр: до nextAttemptAt задание не захватывается повторно
        clock.advance(Duration.ofMillis(500));
        assertThat(service.processDue(10)).isZero();

        // после backoff — вторая попытка (attempts=2), при успехе — DONE
        classifier.failure = null;
        clock.advance(Duration.ofSeconds(1));
        service.processDue(10);
        assertThat(job.status()).isEqualTo(ModerationJobStatus.DONE);
        assertThat(job.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("файл исчез → техническая ошибка → RETRY, растение не тронуто")
    void файл_исчез_retry() {
        submitDueJob(UUID.randomUUID());
        media.present = false;

        service.processDue(10);

        assertThat(jobs.jobs.values().iterator().next().status())
            .isEqualTo(ModerationJobStatus.RETRY);
        assertThat(plants.decisions).isEmpty();
    }

    @Test
    @DisplayName("конфликт решения → job DONE с reasonCode=STALE")
    void конфликт_решения_stale() {
        submitDueJob(UUID.randomUUID());
        plants.conflict = new DecisionConflictException("решение уже зафиксировано: APPROVED");

        service.processDue(10);

        ModerationJob job = jobs.jobs.values().iterator().next();
        assertThat(job.status()).isEqualTo(ModerationJobStatus.DONE);
        assertThat(job.reasonCode()).isEqualTo(ModerationReasonCode.STALE);
    }

    @Test
    @DisplayName("due-фильтр: задание из будущего не обрабатывается")
    void due_фильтр_будущее() {
        UUID plantId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        jobs.save(ModerationJob.create(plantId, assetId, clock.instant().plus(Duration.ofHours(1))));

        assertThat(service.processDue(10)).isZero();
        assertThat(jobs.findLatestByPlantId(plantId).orElseThrow().status())
            .isEqualTo(ModerationJobStatus.NEW);
    }

    private void submitDueJob(UUID plantId) {
        jobs.save(ModerationJob.create(plantId, UUID.randomUUID(), clock.instant()));
    }

    /** Управляемые часы: домен получает время аргументом (раздел 14). */
    private static final class SettableClock extends Clock {

        private Instant now = Instant.parse("2026-09-27T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
```

- [ ] **Step 4: Запустить — красный**

```bash
./mvnw -q test -Dtest='CreateModerationJobServiceTest,ProcessModerationJobsTest'
```

Ожидание: FAIL (компиляция): `cannot find symbol: class CreateModerationJobService / ProcessModerationJobsService / ApplyModerationResultService`.

- [ ] **Step 5: Реализация сервисов**

`src/main/java/com/plantarena/moderation/application/CreateModerationJobService.java`:

```java
package com.plantarena.moderation.application;

import com.plantarena.moderation.application.port.in.CreateModerationJobUseCase;
import com.plantarena.moderation.domain.ModerationJob;
import com.plantarena.moderation.domain.ModerationJobRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * PlantSubmitted → задание NEW. Вызывается синхронным @EventListener внутри
 * транзакции подачи заявки (in-process, раздел 12): задание появляется
 * атомарно с растением. Идемпотентно по plantId (повтор доставки — no-op).
 */
@Service
public class CreateModerationJobService implements CreateModerationJobUseCase {

    private final ModerationJobRepository jobs;
    private final Clock clock;

    public CreateModerationJobService(ModerationJobRepository jobs, Clock clock) {
        this.jobs = jobs;
        this.clock = clock;
    }

    @Override
    public void onPlantSubmitted(UUID plantId, UUID assetId) {
        if (jobs.findLatestByPlantId(plantId).isPresent()) {
            return;
        }
        jobs.save(ModerationJob.create(plantId, assetId, clock.instant()));
    }
}
```

`src/main/java/com/plantarena/moderation/application/ApplyModerationResultService.java`:

```java
package com.plantarena.moderation.application;

import com.plantarena.moderation.application.port.out.PlantClassifier;
import com.plantarena.moderation.application.port.out.PlantModerationGateway;
import com.plantarena.moderation.domain.ModerationJob;
import com.plantarena.moderation.domain.ModerationJobRepository;
import com.plantarena.moderation.domain.ModerationReasonCode;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Короткая tx применения результата (раздел 12, ADR-009): сначала
 * recordDecision (обязательное обновление заявки), затем job DONE —
 * «не ставь DONE до обязательного обновления заявки» (раздел 14).
 * Отступление «одна tx — один агрегат» описано в ADR-009 (план на лабу №2).
 */
@Service
public class ApplyModerationResultService {

    private final ModerationJobRepository jobs;
    private final PlantModerationGateway plantModeration;
    private final Clock clock;

    public ApplyModerationResultService(ModerationJobRepository jobs,
                                        PlantModerationGateway plantModeration, Clock clock) {
        this.jobs = jobs;
        this.plantModeration = plantModeration;
        this.clock = clock;
    }

    @Transactional
    public void apply(ModerationJob job, PlantClassifier.Classification result) {
        boolean plant = result.plant();
        plantModeration.recordDecision(job.plantId(),
            plant ? PlantModerationGateway.Decision.APPROVED : PlantModerationGateway.Decision.REJECTED,
            plant ? ModerationReasonCode.PLANT_DETECTED.name() : ModerationReasonCode.NOT_A_PLANT.name());
        job.succeed(result.modelVersion(), result.confidence(),
            plant ? ModerationReasonCode.PLANT_DETECTED : ModerationReasonCode.NOT_A_PLANT,
            clock.instant());
        jobs.save(job);
    }

    @Transactional
    public void completeStale(ModerationJob job) {
        job.completeStale(clock.instant());
        jobs.save(job);
    }
}
```

`src/main/java/com/plantarena/moderation/application/ProcessModerationJobsService.java`:

```java
package com.plantarena.moderation.application;

import com.plantarena.moderation.application.port.in.ProcessDueModerationJobsUseCase;
import com.plantarena.moderation.application.port.out.MediaContentGateway;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import com.plantarena.moderation.domain.ModerationJob;
import com.plantarena.moderation.domain.ModerationJobRepository;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Обработка due-заданий (раздел 12): короткая tx захвата (claim + save),
 * инференс ВНЕ транзакции, короткая tx применения (ApplyModerationResultService).
 * Техническая ошибка → RETRY (растение остаётся PENDING, ничего не теряется);
 * конфликт решения → DONE/STALE; конкурентный захват ловит @Version.
 */
@Service
public class ProcessModerationJobsService implements ProcessDueModerationJobsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessModerationJobsService.class);

    private final ModerationJobRepository jobs;
    private final MediaContentGateway mediaContent;
    private final PlantClassifier classifier;
    private final ApplyModerationResultService applyResult;
    private final Clock clock;

    public ProcessModerationJobsService(ModerationJobRepository jobs,
                                        MediaContentGateway mediaContent,
                                        PlantClassifier classifier,
                                        ApplyModerationResultService applyResult,
                                        Clock clock) {
        this.jobs = jobs;
        this.mediaContent = mediaContent;
        this.classifier = classifier;
        this.applyResult = applyResult;
        this.clock = clock;
    }

    @Override
    public int processDue(int limit) {
        List<ModerationJob> due = jobs.findDue(clock.instant(), limit);
        int processed = 0;
        for (ModerationJob candidate : due) {
            ModerationJob job = claim(candidate);
            if (job == null) {
                continue;
            }
            process(job);
            processed++;
        }
        return processed;
    }

    /** Короткая tx захвата: claim + save; проигрыш конкуренции — пропускаем. */
    private ModerationJob claim(ModerationJob candidate) {
        try {
            candidate.claim(clock.instant());
        } catch (IllegalStateException alreadyTaken) {
            return null;
        }
        try {
            return jobs.save(candidate);
        } catch (OptimisticLockingFailureException lostRace) {
            log.debug("Задание {} захвачено другим воркером", candidate.id());
            return null;
        }
    }

    /** Инференс вне tx; применение — короткая tx (ApplyModerationResultService). */
    private void process(ModerationJob job) {
        try {
            MediaContentGateway.MediaContent content = mediaContent.loadContent(job.assetId())
                .orElseThrow(() -> new IllegalStateException(
                    "Файл задания не найден: " + job.assetId()));
            PlantClassifier.Classification result = classifier.classify(content.bytes());
            applyResult.apply(job, result);
        } catch (DecisionConflictException staleResult) {
            log.info("Устаревший результат по заявке {}: решение уже есть", job.plantId());
            applyResult.completeStale(job);
        } catch (RuntimeException technical) {
            log.warn("Попытка {} задания {} не удалась: {} — RETRY, растение остаётся PENDING",
                job.attempts(), job.id(), technical.getMessage());
            job.retry(clock.instant());
            jobs.save(job);
        }
    }
}
```

- [ ] **Step 6: Запустить — зелёный**

```bash
./mvnw -q test -Dtest='CreateModerationJobServiceTest,ProcessModerationJobsTest'
```

Ожидание: PASS (8 тестов).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/plantarena/moderation/application src/test/java/com/plantarena/moderation/application
git commit -m "feat(moderation): application — захват/инференс/применение с короткими tx, retry и STALE"
```

---

### Task 4: Хранилище — миграция V2, JPA-адаптер, контрактные тесты

**Files:**
- Create: `src/main/resources/db/migration/moderation/V2__moderation_jobs.sql`
- Create: `src/main/java/com/plantarena/moderation/adapter/out/persistence/ModerationJobJpaEntity.java`
- Create: `src/main/java/com/plantarena/moderation/adapter/out/persistence/ModerationJobJpaRepository.java`
- Create: `src/main/java/com/plantarena/moderation/adapter/out/persistence/JpaModerationJobRepository.java`
- Test: `src/test/java/com/plantarena/moderation/ModerationJobRepositoryContractTest.java`
- Test: `src/test/java/com/plantarena/moderation/application/support/InMemoryModerationJobRepositoryContractTest.java`
- Test: `src/test/java/com/plantarena/moderation/adapter/out/persistence/JpaModerationJobRepositoryContractIT.java`

**Interfaces:**
- Consumes: `ModerationJobRepository` (Task 2), `SchemaMigrationConfig` (миграции по контекстам, ADR-003), паттерн `JpaPlantRepository` (find-or-create + saveAndFlush).
- Produces: бин `JpaModerationJobRepository implements ModerationJobRepository` (для Task 6); таблица `moderation.moderation_job`.

- [ ] **Step 1: Миграция**

`src/main/resources/db/migration/moderation/V2__moderation_jobs.sql` (V1__init.sql — существующий placeholder `SELECT 1`; схема `moderation` создаёт Flyway `.schemas(...)`, ADR-003):

```sql
-- Задания автоматической модерации (контекст moderation, ADR-009).
-- plant_id — ключ команды plants без FK (раздел 10.4: границы контекстов).
CREATE TABLE moderation_job (
    id              UUID PRIMARY KEY,
    plant_id        UUID        NOT NULL,
    asset_id        UUID        NOT NULL,
    status          VARCHAR(13) NOT NULL CHECK (status IN ('NEW','IN_PROGRESS','RETRY','DONE')),
    attempts        INTEGER     NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    model_version   VARCHAR(50),
    confidence      REAL,
    reason_code     VARCHAR(20) CHECK (reason_code IN ('PLANT_DETECTED','NOT_A_PLANT','STALE')),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL,
    version         BIGINT      NOT NULL DEFAULT 0
);

-- Due-опрос: только NEW/RETRY, готовые к попытке (частичный индекс)
CREATE INDEX moderation_job_due_idx
    ON moderation_job (next_attempt_at) WHERE status IN ('NEW','RETRY');

-- Последнее задание по заявке (идемпотентность слушателя)
CREATE INDEX moderation_job_plant_idx ON moderation_job (plant_id, created_at);
```

- [ ] **Step 2: Красный контрактный тест (абстрактный)**

`src/test/java/com/plantarena/moderation/ModerationJobRepositoryContractTest.java`:

```java
package com.plantarena.moderation;

import com.plantarena.moderation.domain.ModerationJob;
import com.plantarena.moderation.domain.ModerationJobRepository;
import com.plantarena.moderation.domain.ModerationJobStatus;
import com.plantarena.moderation.domain.ModerationReasonCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт ModerationJobRepository (раздел 14.2): одинаковые гарантии у
 * in-memory фейка и JPA + PostgreSQL. @Transactional обязателен на базовом
 * классе (урок итерации 2).
 */
@DisplayName("Контракт ModerationJobRepository")
@Transactional
public abstract class ModerationJobRepositoryContractTest {

    protected static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    protected abstract ModerationJobRepository repository();

    private ModerationJob newJob(UUID plantId, Instant nextAttemptAt) {
        return ModerationJob.create(plantId, UUID.randomUUID(), nextAttemptAt);
    }

    @Test
    @DisplayName("сохранение и чтение по id: все поля")
    void сохранение_и_чтение_по_id() {
        ModerationJob job = newJob(UUID.randomUUID(), NOW);

        repository().save(job);
        ModerationJob loaded = repository().findById(job.id()).orElseThrow();

        assertThat(loaded.id()).isEqualTo(job.id());
        assertThat(loaded.plantId()).isEqualTo(job.plantId());
        assertThat(loaded.assetId()).isEqualTo(job.assetId());
        assertThat(loaded.status()).isEqualTo(ModerationJobStatus.NEW);
        assertThat(loaded.attempts()).isZero();
        assertThat(loaded.nextAttemptAt()).isEqualTo(NOW);
        assertThat(loaded.createdAt()).isEqualTo(NOW);
        assertThat(loaded.modelVersion()).isNull();
        assertThat(loaded.confidence()).isNull();
        assertThat(loaded.reasonCode()).isNull();
        assertThat(loaded.startedAt()).isNull();
        assertThat(loaded.completedAt()).isNull();
    }

    @Test
    @DisplayName("мутации claim/succeed/retry переживают сохранение")
    void мутации_переживают_сохранение() {
        ModerationJob job = newJob(UUID.randomUUID(), NOW);
        repository().save(job);

        job.claim(NOW.plusSeconds(1));
        job.succeed("m-v1", 0.5f, ModerationReasonCode.NOT_A_PLANT, NOW.plusSeconds(2));
        repository().save(job);

        ModerationJob loaded = repository().findById(job.id()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(ModerationJobStatus.DONE);
        assertThat(loaded.attempts()).isEqualTo(1);
        assertThat(loaded.modelVersion()).isEqualTo("m-v1");
        assertThat(loaded.confidence()).isEqualTo(0.5f);
        assertThat(loaded.reasonCode()).isEqualTo(ModerationReasonCode.NOT_A_PLANT);
        assertThat(loaded.startedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(loaded.completedAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    @DisplayName("несуществующий id — пустой результат")
    void несуществующий_id_пустой_результат() {
        assertThat(repository().findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("findDue: NEW/RETRY с прошедшим nextAttemptAt, по возрастанию, лимит")
    void findDue_фильтр_порядок_лимит() {
        UUID plantId = UUID.randomUUID();
        ModerationJob future = newJob(plantId, NOW.plusSeconds(3600));
        ModerationJob retryDue = newJob(plantId, NOW.minusSeconds(60));
        retryDue.claim(NOW.minusSeconds(50));
        retryDue.retry(NOW.minusSeconds(50)); // RETRY, nextAttemptAt = NOW-49
        ModerationJob newDue = newJob(plantId, NOW.minusSeconds(5));
        repository().save(future);
        repository().save(retryDue);
        repository().save(newDue);

        List<ModerationJob> due = repository().findDue(NOW, 10);

        assertThat(due).extracting(ModerationJob::id)
            .containsExactly(retryDue.id(), newDue.id()); // по nextAttemptAt
        assertThat(due).extracting(ModerationJob::status)
            .containsOnly(ModerationJobStatus.NEW, ModerationJobStatus.RETRY);

        assertThat(repository().findDue(NOW, 1)).hasSize(1);
        assertThat(repository().findDue(NOW.minusSeconds(3600), 10)).isEmpty();
    }

    @Test
    @DisplayName("findDue: IN_PROGRESS и DONE не due")
    void findDue_не_включает_занятые_и_завершённые() {
        ModerationJob inProgress = newJob(UUID.randomUUID(), NOW);
        inProgress.claim(NOW);
        ModerationJob done = newJob(UUID.randomUUID(), NOW);
        done.claim(NOW);
        done.succeed("m", 0.9f, ModerationReasonCode.PLANT_DETECTED, NOW);
        repository().save(inProgress);
        repository().save(done);

        assertThat(repository().findDue(NOW, 10)).isEmpty();
    }

    @Test
    @DisplayName("findLatestByPlantId: последнее по createdAt")
    void findLatestByPlantId() {
        UUID plantId = UUID.randomUUID();
        ModerationJob first = ModerationJob.restore(UUID.randomUUID(), plantId,
            UUID.randomUUID(), ModerationJobStatus.DONE, 1, NOW, "m", 0.9f,
            ModerationReasonCode.PLANT_DETECTED, NOW, NOW, NOW.minusSeconds(60), 0);
        ModerationJob second = newJob(plantId, NOW);
        repository().save(first);
        repository().save(second);

        assertThat(repository().findLatestByPlantId(plantId)).contains(second);
        assertThat(repository().findLatestByPlantId(UUID.randomUUID())).isEmpty();
    }
}
```

`src/test/java/com/plantarena/moderation/application/support/InMemoryModerationJobRepositoryContractTest.java`:

```java
package com.plantarena.moderation.application.support;

import com.plantarena.moderation.ModerationJobRepositoryContractTest;
import com.plantarena.moderation.domain.ModerationJobRepository;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Контракт ModerationJobRepository: in-memory фейк")
class InMemoryModerationJobRepositoryContractTest extends ModerationJobRepositoryContractTest {

    private final InMemoryModerationJobRepository repository = new InMemoryModerationJobRepository();

    @Override
    protected ModerationJobRepository repository() {
        return repository;
    }
}
```

`src/test/java/com/plantarena/moderation/adapter/out/persistence/JpaModerationJobRepositoryContractIT.java`:

```java
package com.plantarena.moderation.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.moderation.ModerationJobRepositoryContractTest;
import com.plantarena.moderation.domain.ModerationJobRepository;
import com.plantarena.support.PostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Контракт ModerationJobRepository на JPA + PostgreSQL (Testcontainers,
 * раздел 14.2). Миграции — SchemaMigrationConfig (ADR-003), H2 не используется.
 */
@DisplayName("Контракт ModerationJobRepository: JPA + PostgreSQL (Testcontainers)")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JpaModerationJobRepository.class})
class JpaModerationJobRepositoryContractIT extends ModerationJobRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JpaModerationJobRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_задания_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from moderation.moderation_job");
    }

    @Override
    protected ModerationJobRepository repository() {
        return repository;
    }
}
```

- [ ] **Step 3: Запустить — красный**

```bash
./mvnw -q test-compile
```

Ожидание: FAIL (компиляция): `cannot find symbol: class JpaModerationJobRepository` (JPA-наследник контракта не компилируется без адаптера).

- [ ] **Step 4: JPA-адаптер**

`src/main/java/com/plantarena/moderation/adapter/out/persistence/ModerationJobJpaEntity.java`:

```java
package com.plantarena.moderation.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA-модель moderation_job (раздел 11); маппинг на домен — явный (в
 * JpaModerationJobRepository). Конкурентный захват due-заданий ловит @Version.
 */
@Entity
@Table(name = "moderation_job", schema = "moderation")
public class ModerationJobJpaEntity {

    @Id
    private UUID id;

    @Column(name = "plant_id", nullable = false)
    private UUID plantId;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "model_version", length = 50)
    private String modelVersion;

    private Float confidence;

    @Column(name = "reason_code", length = 20)
    private String reasonCode;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ModerationJobJpaEntity() {
    }

    ModerationJobJpaEntity(UUID id, UUID plantId, UUID assetId, String status, int attempts,
                           Instant nextAttemptAt, Instant createdAt) {
        this.id = id;
        this.plantId = plantId;
        this.assetId = assetId;
        this.status = status;
        this.attempts = attempts;
        this.nextAttemptAt = nextAttemptAt;
        this.createdAt = createdAt;
    }

    /** Мутации домена; id/plantId/assetId/createdAt неизменяемы. */
    void update(String status, int attempts, Instant nextAttemptAt, String modelVersion,
                Float confidence, String reasonCode, Instant startedAt, Instant completedAt) {
        this.status = status;
        this.attempts = attempts;
        this.nextAttemptAt = nextAttemptAt;
        this.modelVersion = modelVersion;
        this.confidence = confidence;
        this.reasonCode = reasonCode;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlantId() {
        return plantId;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public String getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public Float getConfidence() {
        return confidence;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }
}
```

`src/main/java/com/plantarena/moderation/adapter/out/persistence/ModerationJobJpaRepository.java`:

```java
package com.plantarena.moderation.adapter.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data для moderation_job; due = NEW/RETRY с прошедшим nextAttemptAt. */
public interface ModerationJobJpaRepository extends JpaRepository<ModerationJobJpaEntity, UUID> {

    List<ModerationJobJpaEntity> findByStatusInAndNextAttemptAtLessThanEqual(
        Collection<String> statuses, Instant nextAttemptAt, Pageable pageable);

    Optional<ModerationJobJpaEntity> findFirstByPlantIdOrderByCreatedAtDesc(UUID plantId);
}
```

`src/main/java/com/plantarena/moderation/adapter/out/persistence/JpaModerationJobRepository.java`:

```java
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
```

- [ ] **Step 5: Запустить — зелёный**

```bash
./mvnw -q test -Dtest='InMemoryModerationJobRepositoryContractTest'
./mvnw -q verify -Dit.test=JpaModerationJobRepositoryContractIT -DfailIfNoTests=false
```

Ожидание: PASS (контракт 6 тестов у каждого наследника). Если `findDue_фильтр_порядок_лимит` падает на JPA из-за равных `nextAttemptAt` — тест создаёт разные значения, порядок стабилен; при равных — tie-break по id (uuid как строки, как в итерации 3).

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/moderation src/main/java/com/plantarena/moderation/adapter/out/persistence src/test/java/com/plantarena/moderation
git commit -m "feat(moderation): хранилище — миграция V2, JPA-адаптер, контрактные тесты (in-memory + Testcontainers)"
```

---

### Task 5: ONNX-адаптер, модель при сборке, wiring, inference-IT по флагу

**Files:**
- Modify: `pom.xml` (onnxruntime, download-maven-plugin, failsafe `excludedGroups`, profile `inference`)
- Create: `src/main/java/com/plantarena/moderation/adapter/out/classifier/OnnxPlantClassifier.java`
- Create: `src/main/java/com/plantarena/config/ModerationWiringConfig.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/resources/moderation/reference/daisy.jpg`, `src/test/resources/moderation/reference/dog.jpg` (скачивание curl)
- Test: `src/test/java/com/plantarena/moderation/adapter/out/classifier/OnnxPlantClassifierIT.java`

**Interfaces:**
- Consumes: `PlantClassifier` (Task 1), `ClassifierUnavailableException` (Task 1).
- Produces: бин `PlantClassifier` (основной профиль, `ModerationWiringConfig`) для Task 6; `OnnxPlantClassifier.MODEL_VERSION = "mobilenetv2-1.0-onnx-imagenet/plant-classes-v1"`; конфиг `plantarena.moderation.model-path`; модель `target/models/mobilenetv2-1.0.onnx` при сборке.

- [ ] **Step 1: pom.xml — onnxruntime, скачивание модели, тег inference**

В `<properties>`:

```xml
    <onnxruntime.version>1.20.0</onnxruntime.version>
    <download-maven-plugin.version>1.13.0</download-maven-plugin.version>
```

В `<dependencies>` (основной scope — адаптер используется в рантайме):

```xml
    <dependency>
      <groupId>com.microsoft.onnxruntime</groupId>
      <artifactId>onnxruntime</artifactId>
      <version>${onnxruntime.version}</version>
    </dependency>
```

В `<build><plugins>` после jacoco-плагина (модель ~13 МБ в `target/models/`; `failOnError=false` — offline-сборка не ломается, отсутствие модели = честная незавершённость, ADR-009):

```xml
      <plugin>
        <groupId>com.googlecode.maven-download-plugin</groupId>
        <artifactId>download-maven-plugin</artifactId>
        <version>${download-maven-plugin.version}</version>
        <executions>
          <execution>
            <id>download-mobilenetv2-onnx</id>
            <phase>generate-test-resources</phase>
            <goals>
              <goal>wget</goal>
            </goals>
            <configuration>
              <url>https://github.com/onnx/models/raw/main/validated/vision/classification/mobilenet/model/mobilenetv2-12.onnx</url>
              <outputDirectory>${project.build.directory}/models</outputDirectory>
              <fileName>mobilenetv2-1.0.onnx</fileName>
              <failOnError>false</failOnError>
            </configuration>
          </execution>
        </executions>
      </plugin>
```

В существующем `maven-failsafe-plugin` добавить в `<configuration>` (inference-IT не входит в обычный verify):

```xml
          <excludedGroups>inference</excludedGroups>
```

После `</build>` (профиль запуска inference-демо: `./mvnw verify -P inference` — только тег `inference`; `excludedGroups` перекрывается значением-пустышкой, т.к. пустая строка в excludedGroups ненадёжна):

```xml
  <profiles>
    <profile>
      <id>inference</id>
      <build>
        <plugins>
          <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-failsafe-plugin</artifactId>
            <configuration>
              <groups>inference</groups>
              <excludedGroups>no-such-tag</excludedGroups>
            </configuration>
          </plugin>
        </plugins>
      </build>
    </profile>
  </profiles>
```

- [ ] **Step 2: application.yml**

Добавить в `plantarena:`:

```yaml
  moderation:
    model-path: ${MODERATION_MODEL_PATH:target/models/mobilenetv2-1.0.onnx} # MobileNetV2 ONNX, ADR-009
```

- [ ] **Step 3: OnnxPlantClassifier**

`src/main/java/com/plantarena/moderation/adapter/out/classifier/OnnxPlantClassifier.java`:

```java
package com.plantarena.moderation.adapter.out.classifier;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.plantarena.moderation.application.port.out.ClassifierUnavailableException;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Классификатор MobileNetV2 (ImageNet-1K) через ONNX Runtime внутри монолита
 * (ADR-009). Препроцессинг: decode → resize 224×224 RGB → нормализация
 * ImageNet → NCHW float. Решение: top-1 ∈ растительные классы ImageNet-1K и
 * confidence ≥ 0.35. Отдельных классов «дерево» в ImageNet-1K нет — деревья
 * представлены плодами/семенами (жёлудь, конский каштан, инжир; ADR-009).
 * Сессия создаётся на каждый вызов: для лабы №1 нагрузка мизерная, состояние
 * не держим; пул сессий — лаба №2 при выносе inference-сервиса.
 * Отсутствие файла модели — ClassifierUnavailableException (job RETRY,
 * растение PENDING: честная незавершённость, раздел 6).
 */
public class OnnxPlantClassifier implements PlantClassifier {

    public static final String MODEL_VERSION = "mobilenetv2-1.0-onnx-imagenet/plant-classes-v1";
    static final float CONFIDENCE_THRESHOLD = 0.35f;
    private static final int INPUT_SIZE = 224;
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    /** Растительные классы ImageNet-1K (индексы стандартного маппинга, ADR-009). */
    static final Set<Integer> PLANT_CLASS_INDICES = Set.of(
        936, 937, 938, 941, 944,       // капуста, брокколи, цветная капуста, сквош, артишок
        950, 951, 952, 953, 954, 956,  // апельсин, лимон, инжир, ананас, банан, аннона
        958,                           // сено
        984, 985, 986, 987,            // рапс, ромашка, венерин башмачок, кукуруза
        988, 989, 990, 998);           // жёлудь, плод шиповника, конский каштан, початок

    private final Path modelPath;

    public OnnxPlantClassifier(Path modelPath) {
        this.modelPath = modelPath;
    }

    @Override
    public Classification classify(byte[] imageBytes) {
        if (!Files.isReadable(modelPath)) {
            throw new ClassifierUnavailableException(
                "Модель классификатора не найдена: " + modelPath.toAbsolutePath()
                    + " — см. README «Модерация» (mvnw сам скачивает модель при сборке; "
                    + "offline-сборка оставляет задания в RETRY)");
        }
        float[] probabilities = runInference(preprocess(imageBytes));
        int top = argmax(probabilities);
        float confidence = probabilities[top];
        boolean plant = PLANT_CLASS_INDICES.contains(top) && confidence >= CONFIDENCE_THRESHOLD;
        return new Classification(plant, confidence, MODEL_VERSION);
    }

    /** decode → resize 224×224 RGB → нормализация ImageNet → NCHW. */
    private float[] preprocess(byte[] imageBytes) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException e) {
            throw new ClassifierUnavailableException("Не удалось декодировать изображение", e);
        }
        if (source == null) {
            throw new ClassifierUnavailableException("Не удалось декодировать изображение");
        }
        BufferedImage resized = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, INPUT_SIZE, INPUT_SIZE, null);
        } finally {
            graphics.dispose();
        }
        float[] tensor = new float[3 * INPUT_SIZE * INPUT_SIZE];
        int plane = INPUT_SIZE * INPUT_SIZE;
        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                int rgb = resized.getRGB(x, y);
                int pixel = y * INPUT_SIZE + x;
                tensor[pixel] = (((rgb >> 16) & 0xFF) / 255f - MEAN[0]) / STD[0];
                tensor[plane + pixel] = (((rgb >> 8) & 0xFF) / 255f - MEAN[1]) / STD[1];
                tensor[2 * plane + pixel] = ((rgb & 0xFF) / 255f - MEAN[2]) / STD[2];
            }
        }
        return tensor;
    }

    private float[] runInference(float[] input) {
        try (OrtEnvironment environment = OrtEnvironment.getEnvironment();
             OrtSession.SessionOptions options = new OrtSession.SessionOptions();
             OrtSession session = environment.createSession(modelPath.toString(), options);
             OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input),
                 new long[] {1, 3, INPUT_SIZE, INPUT_SIZE})) {
            String inputName = session.getInputNames().iterator().next();
            try (OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
                float[][] logits = (float[][]) result.get(0).getValue();
                return softmax(logits[0]);
            }
        } catch (Exception e) {
            throw new ClassifierUnavailableException("Инференс ONNX не удался", e);
        }
    }

    private static int argmax(float[] values) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[best]) {
                best = i;
            }
        }
        return best;
    }

    private static float[] softmax(float[] logits) {
        float max = Float.NEGATIVE_INFINITY;
        for (float value : logits) {
            max = Math.max(max, value);
        }
        float[] probabilities = new float[logits.length];
        double sum = 0;
        for (int i = 0; i < logits.length; i++) {
            probabilities[i] = (float) Math.exp(logits[i] - max);
            sum += probabilities[i];
        }
        for (int i = 0; i < probabilities.length; i++) {
            probabilities[i] /= (float) sum;
        }
        return probabilities;
    }
}
```

- [ ] **Step 4: Wiring**

`src/main/java/com/plantarena/config/ModerationWiringConfig.java`:

```java
package com.plantarena.config;

import com.plantarena.moderation.adapter.out.classifier.OnnxPlantClassifier;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Связывание классификатора модерации (ADR-009): ONNX-адаптер основного
 * профиля; путь модели — конфиг plantarena.moderation.model-path. Бин не
 * требует файла модели при создании (честная незавершённость при classify).
 * @EnableScheduling — poller заданий модерации (fixedDelay 2с). config —
 * единственное место, знающее несколько контекстов (раздел 10.2, правило 9).
 */
@Configuration
@EnableScheduling
public class ModerationWiringConfig {

    @Bean
    public PlantClassifier plantClassifier(
            @Value("${plantarena.moderation.model-path}") String modelPath) {
        return new OnnxPlantClassifier(Path.of(modelPath));
    }
}
```

- [ ] **Step 5: Эталонные фотографии (Wikimedia Commons, CC)**

```bash
mkdir -p src/test/resources/moderation/reference
curl -sL -H "User-Agent: plantarena-lab/1.0" \
  -o src/test/resources/moderation/reference/daisy.jpg \
  "https://commons.wikimedia.org/wiki/Special:FilePath/Five_daisies_%28Bellis_perennis%29.jpg?width=400"
curl -sL -H "User-Agent: plantarena-lab/1.0" \
  -o src/test/resources/moderation/reference/dog.jpg \
  "https://commons.wikimedia.org/wiki/Special:FilePath/Golden_Retriever_dog_at_MAV-USP.jpg?width=400"
file src/test/resources/moderation/reference/daisy.jpg src/test/resources/moderation/reference/dog.jpg
```

Ожидание: оба `JPEG image data` (~45 КБ и ~23 КБ). Происхождение указать в комментарии IT (шаг 6).

- [ ] **Step 6: Inference-IT (тег inference)**

`src/test/java/com/plantarena/moderation/adapter/out/classifier/OnnxPlantClassifierIT.java`:

```java
package com.plantarena.moderation.adapter.out.classifier;

import com.plantarena.moderation.application.port.out.PlantClassifier;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Настоящий ONNX-инференс MobileNetV2 на эталонных фотографиях (ADR-009).
 * Тег inference: в обычный verify не входит (failsafe excludedGroups);
 * запуск: ./mvnw verify -P inference. Без модели (offline-сборка) — честный
 * skip с сообщением (assumption), не ошибка.
 * Фото: Wikimedia Commons — «Five daisies (Bellis perennis)» и
 * «Golden Retriever dog at MAV-USP» (уменьшенные до 400px, CC-лицензии
 * указаны на страницах файлов).
 */
@Tag("inference")
@DisplayName("ONNX-инференс MobileNetV2: ромашка — растение, собака — нет (ADR-009)")
class OnnxPlantClassifierIT {

    private static final Path MODEL = Path.of("target/models/mobilenetv2-1.0.onnx");

    private final OnnxPlantClassifier classifier = new OnnxPlantClassifier(MODEL);

    @BeforeAll
    static void модель_должна_быть_скачана() {
        Assumptions.assumeTrue(Files.isReadable(MODEL),
            "Модель не скачана (offline): " + MODEL.toAbsolutePath()
                + " — повторите сборку онлайн или скачайте вручную (README «Модерация»)");
    }

    @Test
    @DisplayName("фото ромашки распознаётся как растение")
    void ромашка_растение() throws Exception {
        PlantClassifier.Classification result = classifier.classify(reference("daisy.jpg"));

        assertThat(result.modelVersion()).isEqualTo(OnnxPlantClassifier.MODEL_VERSION);
        assertThat(result.confidence()).isBetween(0f, 1f);
        assertThat(result.plant())
            .as("top-1 должен попасть в растительные классы с confidence >= 0.35, "
                + "фактическая confidence = %s", result.confidence())
            .isTrue();
    }

    @Test
    @DisplayName("фото собаки — не растение")
    void собака_не_растение() throws Exception {
        PlantClassifier.Classification result = classifier.classify(reference("dog.jpg"));

        assertThat(result.plant())
            .as("фактическая confidence = %s", result.confidence())
            .isFalse();
    }

    private byte[] reference(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/moderation/reference/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Эталонное фото не найдено: " + name);
            }
            return in.readAllBytes();
        }
    }
}
```

- [ ] **Step 7: Запуск**

```bash
./mvnw -q verify
```

Ожидание: BUILD SUCCESS; `OnnxPlantClassifierIT` не выполнялся (excludedGroups=inference); модель скачана в `target/models/mobilenetv2-1.0.onnx` (лог download-maven-plugin; при offline — предупреждение, сборка не падает).

```bash
./mvnw verify -P inference
```

Ожидание: выполняется только `OnnxPlantClassifierIT` — PASS (2 теста). Если `ромашка_растение` падает: посмотреть в сообщении фактическую confidence; MobileNetV2 на фото ромашки крупным планом уверенно даёт daisy (985) — при ином top-1 (например, «vase» 883) добавить индекс в `PLANT_CLASS_INDICES` и упомянуть в ADR-009 (список — часть modelVersion).

- [ ] **Step 8: Commit**

```bash
git add pom.xml src/main/resources/application.yml src/main/java/com/plantarena/moderation/adapter/out/classifier src/main/java/com/plantarena/config/ModerationWiringConfig.java src/test/resources/moderation src/test/java/com/plantarena/moderation/adapter
git commit -m "feat(moderation): ONNX-классификатор MobileNetV2, модель при сборке, inference-демо по флагу -P inference"
```

---

### Task 6: Связка — слушатель события, poller, ACL-адаптеры; ModerationApiIT зелёный

**Files:**
- Create: `src/main/java/com/plantarena/moderation/adapter/in/events/PlantSubmittedHandler.java`
- Create: `src/main/java/com/plantarena/moderation/adapter/in/jobs/ModerationJobPoller.java`
- Create: `src/main/java/com/plantarena/moderation/adapter/out/media/InProcessMediaContentGateway.java`
- Create: `src/main/java/com/plantarena/moderation/adapter/out/plants/InProcessPlantModerationGateway.java`
- Modify: `src/test/java/com/plantarena/architecture/ContextBoundaryTest.java` (adapter.in.events — ACL-место)
- Test: `src/test/java/com/plantarena/moderation/ModerationApiIT.java` (уже красный, становится зелёным)

**Interfaces:**
- Consumes: `CreateModerationJobUseCase`, `ProcessDueModerationJobsUseCase` (Task 3), `MediaContentGateway`, `PlantModerationGateway`, `DecisionConflictException` (Task 3), `media.api.MediaAssets.loadContent` (Task 1), `plants.api.PlantModeration`, `plants.api.ModerationAlreadyDecidedException`, `plants.api.event.PlantSubmittedEvent` (итерация 3), `OnnxPlantClassifier`-бин (Task 5).
- Produces: полная связка контекста moderation; зелёный `ModerationApiIT`.

- [ ] **Step 1: ACL-адаптеры (сначала — они не имеют своих тестов, покрываются IT)**

`src/main/java/com/plantarena/moderation/adapter/out/media/InProcessMediaContentGateway.java`:

```java
package com.plantarena.moderation.adapter.out.media;

import com.plantarena.media.api.MediaAssets;
import com.plantarena.moderation.application.port.out.MediaContentGateway;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ACL-адаптер media → moderation (раздел 4.3, Customer–Supplier): байты файла
 * через опубликованный контракт media.api; storageKey не пересекает границу.
 * Права не проверяются: вызов внутреннего контракта монолита по assetId из
 * задания модерации. В лабе №2 меняется на HTTP-клиент file-service.
 */
@Component
public class InProcessMediaContentGateway implements MediaContentGateway {

    private final MediaAssets mediaAssets;

    public InProcessMediaContentGateway(MediaAssets mediaAssets) {
        this.mediaAssets = mediaAssets;
    }

    @Override
    public Optional<MediaContent> loadContent(UUID assetId) {
        return mediaAssets.loadContent(assetId)
            .map(content -> new MediaContent(content.content(), content.mimeType()));
    }
}
```

`src/main/java/com/plantarena/moderation/adapter/out/plants/InProcessPlantModerationGateway.java`:

```java
package com.plantarena.moderation.adapter.out.plants;

import com.plantarena.moderation.application.DecisionConflictException;
import com.plantarena.moderation.application.port.out.PlantModerationGateway;
import com.plantarena.plants.api.ModerationAlreadyDecidedException;
import com.plantarena.plants.api.PlantModeration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ACL-адаптер plants → moderation (раздел 4.3): решение передаётся командой
 * plants.api.PlantModeration.recordDecision. Конфликт решения по уже
 * решённой заявке переводится в термины moderation (DecisionConflictException
 * → задание завершается DONE/STALE). В лабе №2 меняется на HTTP-клиент.
 */
@Component
public class InProcessPlantModerationGateway implements PlantModerationGateway {

    private final PlantModeration plantModeration;

    public InProcessPlantModerationGateway(PlantModeration plantModeration) {
        this.plantModeration = plantModeration;
    }

    @Override
    public void recordDecision(UUID plantId, Decision decision, String reason) {
        try {
            plantModeration.recordDecision(plantId,
                PlantModeration.Decision.valueOf(decision.name()), reason);
        } catch (ModerationAlreadyDecidedException e) {
            throw new DecisionConflictException(e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Слушатель события и poller**

`src/main/java/com/plantarena/moderation/adapter/in/events/PlantSubmittedHandler.java`:

```java
package com.plantarena.moderation.adapter.in.events;

import com.plantarena.moderation.application.port.in.CreateModerationJobUseCase;
import com.plantarena.plants.api.event.PlantSubmittedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Подписка на PlantSubmitted (in-process Spring-событие, конверт
 * IntegrationEvent): moderation — downstream plants (раздел 4.3). Слушатель
 * живёт в adapter.in.events — ACL-место по context map (чужой контекст —
 * только его api). Синхронный вызов внутри транзакции подачи: задание
 * появляется атомарно с растением (раздел 12). В лабе №4 заменяется
 * Kafka-слушателем без изменения use case.
 */
@Component
public class PlantSubmittedHandler {

    private final CreateModerationJobUseCase createModerationJob;

    public PlantSubmittedHandler(CreateModerationJobUseCase createModerationJob) {
        this.createModerationJob = createModerationJob;
    }

    @EventListener
    public void onPlantSubmitted(PlantSubmittedEvent event) {
        createModerationJob.onPlantSubmitted(event.payload().plantId(),
            event.payload().assetId());
    }
}
```

`src/main/java/com/plantarena/moderation/adapter/in/jobs/ModerationJobPoller.java`:

```java
package com.plantarena.moderation.adapter.in.jobs;

import com.plantarena.moderation.application.port.in.ProcessDueModerationJobsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Опрос due-заданий модерации: fixedDelay 2с, пачка ≤ 10 (спека итерации 4).
 * Транзакционную структуру (захват → инференс вне tx → применение) решает
 * use case; poller только вызывает его и не даёт планировщику умереть.
 */
@Component
public class ModerationJobPoller {

    private static final Logger log = LoggerFactory.getLogger(ModerationJobPoller.class);
    private static final int BATCH_SIZE = 10;

    private final ProcessDueModerationJobsUseCase processDueJobs;

    public ModerationJobPoller(ProcessDueModerationJobsUseCase processDueJobs) {
        this.processDueJobs = processDueJobs;
    }

    @Scheduled(fixedDelay = 2000)
    public void poll() {
        try {
            processDueJobs.processDue(BATCH_SIZE);
        } catch (RuntimeException e) {
            log.error("Цикл обработки заданий модерации не удался (продолжаем): {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 3: ContextBoundaryTest — adapter.in.events как ACL-место**

В `src/test/java/com/plantarena/architecture/ContextBoundaryTest.java` заменить метод `isAclAdapter` (правило уже записано в context-map.md: «только из своих адаптеров (adapter.out.<ctx> / adapter.in.events)» — это признание существующего правила, не ослабление):

```java
    /** adapter.out — выходные ACL-адаптеры; adapter.in.events — входные
     *  подписчики на события чужих api (context map, раздел 4.3). */
    private static boolean isAclAdapter(String packageName) {
        return ownContextOf(packageName) != null
            && (packageName.matches("com\\.plantarena\\.(\\w+)\\.adapter\\.out\\..*")
                || packageName.matches("com\\.plantarena\\.(\\w+)\\.adapter\\.in\\.events\\..*"));
    }
```

- [ ] **Step 4: Запустить ArchUnit и приёмочный IT**

```bash
./mvnw -q test -Dtest='ContextBoundaryTest,LayerRulesTest'
```

Ожидание: PASS — moderation-адаптеры обращаются к чужим контекстам только через `media.api`/`plants.api`; `PlantSubmittedHandler` (adapter.in.events) признан ACL-местом.

```bash
./mvnw -q verify -Dit.test=ModerationApiIT -DfailIfNoTests=false
```

Ожидание: `ModerationApiIT` PASS (2 теста): зелёное → APPROVED + файл публичен чужим; красное → REJECTED + NOT_A_PLANT + retryUploadAllowed. Если падает по таймауту Awaitility — смотреть лог: poller должен запускаться (проверить, что `ModerationWiringConfig` с `@EnableScheduling` подхватился, `@Primary`-детерминированный классификатор выиграл у ONNX-бина).

- [ ] **Step 5: Полный verify**

```bash
./mvnw -q verify
```

Ожидание: BUILD SUCCESS, все unit + IT + ArchUnit зелёные, JaCoCo LINE ≥ 70% (gate в конце verify). В логах других IT возможны WARN `Попытка N задания ... не удалась` — это ожидаемая честная незавершённость (ONNX-модели нет в их контексте, задания уходят в RETRY; ADR-009).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/plantarena/moderation/adapter src/test/java/com/plantarena/architecture/ContextBoundaryTest.java
git commit -m "feat(moderation): связка — слушатель PlantSubmitted, poller, ACL media/plants; приёмочный IT зелёный"
```

---

### Task 7: Документация — ADR-009, глоссарий, агрегаты, context map, README

**Files:**
- Create: `docs/domain/adr/ADR-009-plant-moderation-onnx.md`
- Modify: `docs/domain/glossary.md`
- Modify: `docs/domain/aggregates.md`
- Modify: `docs/domain/context-map.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: всё, реализованное в Tasks 1–6.
- Produces: документация, замыкающая итерацию (Definition of done).

- [ ] **Step 1: ADR-009**

`docs/domain/adr/ADR-009-plant-moderation-onnx.md`:

```markdown
# ADR-009: Автоматическая модерация — MobileNetV2 ONNX внутри монолита

Дата: 2026-09-27. Статус: принято (итерация 4).

## Контекст

Раздел 6 требований: поданная заявка «это моё растение» проходит автоматическое
распознавание; ошибка распознавателя — не решение (растение остаётся PENDING,
ничего не теряется). Решение (APPROVED/REJECTED) передаётся командой
`plants.api.PlantModeration.recordDecision` (раздел 4.3).

## Решение

1. **Классификатор — ONNX Runtime внутри монолита**: зависимость
   `com.microsoft.onnxruntime:onnxruntime`, адаптер
   `moderation.adapter.out.classifier.OnnxPlantClassifier`, модель MobileNetV2
   (ImageNet-pretrained). Порт `PlantClassifier` позволяет вынести
   inference-сервис в лабе №2, не меняя домен и application.
2. **Модель скачивается при сборке**: maven-download-plugin качает
   `mobilenetv2-12.onnx` (~13 МБ) в `target/models/mobilenetv2-1.0.onnx`;
   `failOnError=false` — offline-сборка не ломается. Отсутствие модели —
   честная незавершённость: `ClassifierUnavailableException` при classify,
   job RETRY, plant PENDING (ясное сообщение в логе и README).
3. **Порог и классы**: `plant = top-1 ∈ PLANT_CLASS_INDICES && confidence ≥ 0.35`
   (softmax top-1). ImageNet-1K не содержит отдельных классов «дерево» —
   деревья представлены плодами/семенами (жёлудь 988, конский каштан 990,
   инжир 952) и цветами/сельхозрастениями (ромашка 985, венерин башмачок 986,
   кукуруза 987/998, рапс 984 и др. — полный список в коде адаптера).
   Список классов входит в `modelVersion` (наблюдаемость изменений).
4. **Retry без лимита с капом backoff**: экспоненциальный backoff 1с → 2с →
   4с → … → кап 1ч; attempts — наблюдаемость, не лимит (раздел 6: растение
   остаётся PENDING, ничего не теряется).
5. **Транзакции (отступление от «одна tx — один агрегат», раздел 12)**:
   захват due-задания — короткая tx (claim + save, конкурентный захват ловит
   @Version); инференс вне tx; применение — одна tx: сначала
   `PlantModeration.recordDecision` (обязательное обновление заявки), затем
   job DONE («не ставь DONE до обязательного обновления заявки», раздел 14).
   В лабе №2 применение становится saga/компенсацией между сервисами.
6. **Устаревший результат**: конфликт решения
   (`ModerationAlreadyDecidedException` → `DecisionConflictException`) —
   job DONE с reasonCode=STALE; повтор того же решения — no-op (идемпотентность
   plants). Новая заявка (переотправка) — новый Plant с новым id.
7. **Inference-демо**: тег `inference` у `OnnxPlantClassifierIT`; в обычный
   `verify` не входит (failsafe excludedGroups), запуск `./mvnw verify -P
   inference`; без модели — честный skip (assumption), не ошибка.
8. **Сессия ONNX на каждый вызов**: нагрузка лабы №1 мизерная, состояние не
   держим; пул сессий — при выносе inference-сервиса (лаба №2).

## Последствия

- Байты изображения: контракт `media.api.MediaAssets.loadContent` (mimeType, не
  доменный ImageFormat — api не зависит от domain); storageKey не пересекает
  границу media. Права не проверяются: внутренний вызов монолита по assetId
  из задания.
- `adapter.in.events` признан ACL-местом в ContextBoundaryTest (правило уже
  было записано в context-map.md; слушатель PlantSubmittedHandler знает
  plants.api и moderation.application).
- Тесты/демо без модели: `DeterministicPlantClassifier` (зелёное изображение —
  растение) через @TestConfiguration в ModerationApiIT.
- Не входит (другие итерации/лабы): перевод invitation в tournaments при
  решении — итерация 5; уведомления — лаба №4; inference-сервис — лаба №2.
```

- [ ] **Step 2: Глоссарий**

В `docs/domain/glossary.md` после строки «Задание модерации» добавить:

```markdown
| Классификатор растений | `PlantClassifier` | moderation | Порт распознавания: байты изображения → растение/не растение с уверенностью; адаптеры — ONNX (основной) и детерминированный (тесты) |
| Инференс | `OnnxPlantClassifier` / тег `inference` | moderation | Прямой прогон модели ONNX Runtime внутри монолита (ADR-009); демо: `./mvnw verify -P inference` |
```

- [ ] **Step 3: aggregates.md**

В `docs/domain/aggregates.md` строку модерации заменить (было «(итерация 4)»):

```markdown
| moderation | `ModerationJob` | plantId, assetId, status, attempts, nextAttemptAt, результат | Ошибка распознавателя не является решением; DONE терминален; устаревший результат не применяется; retry без лимита, backoff кап 1ч | создать задание, выполнить попытку, применить результат | — | `ModerationJobTest` (переходы/инварианты/backoff-кап), `ProcessModerationJobsTest` (успех/ошибка→RETRY/конфликт→STALE), `ModerationJobRepositoryContractTest` + `JpaModerationJobRepositoryContractIT`, `ModerationApiIT` (через HTTP) |
```

- [ ] **Step 4: context-map.md**

В `docs/domain/context-map.md`: в mermaid-диаграмме метку ребра `moderation -->|ACL: asset| media` заменить на `moderation -->|ACL: loadContent (байты)| media`; в таблице «Допустимые зависимости» строку moderation заменить на:

```markdown
| moderation | plants, media | Downstream: подписан на `PlantSubmitted` (adapter.in.events), решение командой `plants.api.PlantModeration.recordDecision`; байты файла — `media.api.MediaAssets.loadContent` (ACL adapter.out.media) |
```

- [ ] **Step 5: README**

В `README.md` после раздела «Растения (plants)» добавить раздел (и упомянуть `./mvnw verify -P inference` в разделе «Проверка» одной строкой):

```markdown
## Модерация (moderation)

Поданная заявка проходит автоматическое распознавание (ADR-009): MobileNetV2
(ONNX Runtime) внутри монолита. Зелёное на фото — не гарантия: решает
классификатор; ошибка распознавателя — не решение, заявка остаётся PENDING и
будет повторена (backoff 1с → 2с → … → кап 1ч, без лимита попыток).

- Модель (~13 МБ) скачивается автоматически при сборке в
  `target/models/mobilenetv2-1.0.onnx` (offline-сборка не ломается: задания
  честно уходят в RETRY, в логе видно `ClassifierUnavailableException`).
- Ручной запуск: `MODERATION_MODEL_PATH=... ./mvnw spring-boot:run`.
- Настоящий инференс на эталонных фото (ромашка/собака):
  `./mvnw verify -P inference` (в обычный `verify` не входит).
- Решение видно владельцу: `GET /api/v1/plants/{id}/moderation` — статус,
  причина (`PLANT_DETECTED`/`NOT_A_PLANT`) и `retryUploadAllowed`.
```

- [ ] **Step 6: Финальная проверка и commit**

```bash
./mvnw -q verify && ./mvnw -q verify -P inference
```

Ожидание: оба BUILD SUCCESS; JaCoCo LINE ≥ 70%.

```bash
git add docs README.md
git commit -m "docs: ADR-009 (модерация MobileNetV2 ONNX), глоссарий, агрегаты, context map, README"
```

---

## Definition of Done (итерация 4)

- `./mvnw verify` зелёный (unit + IT + ArchUnit + JaCoCo LINE ≥ 70%); `./mvnw verify -P inference` зелёный на эталонных фото.
- Сквозной сценарий «загрузка → заявка → задание → решение → публичность» через HTTP покрыт `ModerationApiIT` (Testcontainers, детерминированный классификатор).
- Отсутствие модели = честная незавершённость: job RETRY, plant PENDING, ясно в логе (проверено WARN-сообщением в IT-логах других контекстов).
- Docs замкнуты: ADR-009, глоссарий, aggregates, context-map, README.
