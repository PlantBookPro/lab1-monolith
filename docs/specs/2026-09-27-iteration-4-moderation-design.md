# Дизайн итерации 4: moderation — автоматическая модерация заявок

Дата: 2026-09-27. Статус: согласован. Источник: `docs/requirements.md`
(разделы 6, 12, 14), `docs/plans/2026-09-23-roadmap.md` (итерация 4).

## Решения (согласованы)

1. **Классификатор — ONNX внутри монолита**: зависимость
   `com.microsoft.onnxruntime:onnxruntime`, адаптер
   `moderation.adapter.out.classifier.OnnxPlantClassifier`, модель MobileNetV2
   (ImageNet-pretrained). Без отдельного inference-сервиса в лабе №1; порт
   позволяет вынести в лабе №2.
2. **Модель скачивается при сборке**: maven-download-plugin качает
   `mobilenetv2-1.0.onnx` (~13 МБ) в `target/models/`; путь — конфиг
   `moderation.model-path`; offline-сборка не ломается (файл опционален,
   отсутствие модели = честная незавершённость, job RETRY, plant PENDING).
3. **Retry без лимита с капом backoff**: экспоненциальный backoff 1с → 2с →
   4с → … → кап 1ч; attempts — наблюдаемость, не лимит. Растение остаётся
   PENDING, ничего не теряется (требования раздела 6).

## Архитектура и поток

Контекст `moderation` — downstream от plants и media (раздел 4.3).

```
PlantSubmitted (in-process Spring event, конверт IntegrationEvent)
  → @EventListener (adapter.in.events): CreateModerationJobUseCase
    → ModerationJob.create(plantId, assetId, now) — NEW, nextAttemptAt=now
@Scheduled poller (adapter.in.jobs, fixedDelay 2с, пачка ≤ 10):
  1. короткая tx: захват due-задания (NEW/RETRY → IN_PROGRESS, attempts++,
     startedAt)
  2. вне tx: media.api.MediaAssets.loadContent(assetId) → байты →
     PlantClassifier.classify(bytes)
  3. короткая tx: применение результата
     SUCCESS → PlantModeration.recordDecision(APPROVED/REJECTED, reason)
               → job DONE (modelVersion, confidence, reasonCode)
     ошибка  → job RETRY (nextAttemptAt = now + backoff), plant остаётся PENDING
     конфликт решения (ModerationAlreadyDecidedException) → job DONE/STALE
```

Транзакции — раздел 12 требований: короткая tx захвата, вычисление вне tx,
короткая tx применения с проверкой версии (optimistic locking job + Plant).

**Доступ к байтам изображения**: расширение контракта `media.api` методом
`MediaAssets.loadContent(assetId)` → `MediaContent(bytes, format)`. media
читает из своего `FileStorage`; storageKey не пересекает границу. Модерация
зовёт через свой ACL-адаптер `moderation.adapter.out.media` (Customer–Supplier,
как `plants.adapter.out.media` в итерации 3). Права не проверяются: вызов
внутреннего контракта монолита по assetId из job.

**Устаревший результат**: job хранит plantId + assetId; `recordDecision`
идемпотентен по тому же решению, конфликт кидает
`ModerationAlreadyDecidedException` → job завершается DONE с reasonCode
`STALE` (решение по заявке уже есть, повтор не применяется). Новая заявка
(переотправка) — новый Plant с новым id; старый job не может её одобрить.

## Домен: агрегат ModerationJob

`moderation.domain.ModerationJob` (чистая Java, время аргументом):

- Поля: `id`, `plantId`, `assetId`, `status`, `attempts`, `nextAttemptAt`,
  `modelVersion`, `confidence`, `reasonCode`, `startedAt`, `completedAt`,
  `createdAt`, `version` (optimistic locking)
- Статусы: `NEW` → `IN_PROGRESS` → `DONE` | `RETRY`; RETRY → IN_PROGRESS
  (без лимита)
- Фабрика: `create(plantId, assetId, now)` — NEW, attempts=0, nextAttemptAt=now
- Команды:
  - `claim(now)` — NEW/RETRY → IN_PROGRESS, attempts++, startedAt (первая
    попытка)
  - `succeed(modelVersion, confidence, reasonCode, now)` — IN_PROGRESS → DONE,
    completedAt; confidence/reasonCode обязательны
  - `retry(now)` — IN_PROGRESS → RETRY, nextAttemptAt = now +
    backoff(attempts) (1с, 2с, 4с, …, кап 1ч)
  - `completeStale(now)` — IN_PROGRESS → DONE, reasonCode=STALE
- Инварианты: DONE терминален; переходы только по перечисленным рёбрам;
  reasonCode — enum `PLANT_DETECTED` / `NOT_A_PLANT` / `STALE`, заполняется
  только при DONE
- Порт `ModerationJobRepository`: `save`, `findById`, `findDue(now, limit)`
  (NEW/RETRY, nextAttemptAt <= now, по nextAttemptAt), `findLatestByPlantId`

**Применение решения** (`ApplyModerationResultUseCase`): одна транзакция;
порядок — сначала `PlantModeration.recordDecision`, затем job DONE («не ставь
DONE до обязательного обновления заявки», раздел 14).

## Порт PlantClassifier и адаптеры

Порт (moderation.application.port.out, потребитель владеет типами):

```java
public interface PlantClassifier {
    Classification classify(byte[] imageBytes);
    record Classification(boolean plant, float confidence, String modelVersion) {}
    // ClassifierUnavailableException — техническая ошибка → retry
}
```

**OnnxPlantClassifier** (`adapter.out.classifier`):
- onnxruntime InferenceSession, модель из `moderation.model-path`
- Препроцессинг (javax.imageio): decode → resize 224×224 → RGB → нормализация
  ImageNet (mean/std) → float tensor NCHW
- Решение: top-1 класс ImageNet; фиксированный в коде список «растительных»
  индексов (деревья, цветы, комнатные растения; константа с версией);
  `plant = top1 ∈ plantClasses && confidence >= 0.35`
- modelVersion = `mobilenetv2-1.0-onnx-imagenet` + версия списка классов
- Отсутствие файла модели → `ClassifierUnavailableException` при classify()
  (честная незавершённость: job RETRY, plant PENDING, ясно в логе)

**DeterministicPlantClassifier** (только test/demo): правило по содержимому
изображения (например, зелёный PNG → plant=true) — детерминирован, без сети.

**ADR-009**: модель, порог, список классов, retry, скачивание модели.

## Данные и связка

Миграция `db/migration/moderation/V1__moderation_jobs.sql` (схема
`moderation`, Flyway по контекстам — ADR-003):

```sql
CREATE TABLE moderation_job (
    id              UUID PRIMARY KEY,
    plant_id        UUID        NOT NULL,   -- ключ команды, без FK (раздел 10.4)
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
CREATE INDEX moderation_job_due_idx
    ON moderation_job (next_attempt_at) WHERE status IN ('NEW','RETRY');
CREATE INDEX moderation_job_plant_idx ON moderation_job (plant_id, created_at);
```

JPA-адаптер: `ModerationJobJpaEntity`, `JpaModerationJobRepository`
(find-or-create + saveAndFlush) + контрактные тесты (in-memory фейк + JPA IT).

Связка (`config.ModerationWiringConfig` / компоненты адаптеров):
- `OnnxPlantClassifier` — бин основного профиля
- `@EventListener` на `PlantSubmittedEvent` → `CreateModerationJobUseCase`
- `@Scheduled(fixedDelay = 2s)` → `ProcessDueModerationJobsUseCase`
- Тесты: детерминированный адаптер через `@TestConfiguration`

## Тестирование

- `ModerationJobTest` — переходы, инварианты, backoff-кап, фабрика
- `CreateModerationJobServiceTest` — событие создаёт job NEW
- `ProcessModerationJobsTest` — SUCCESS → DONE + recordDecision (одна tx);
  ошибка → RETRY + plant PENDING; конфликт → DONE/STALE; due-фильтр
- `ModerationJobRepositoryContractTest` + in-memory/JPA-наследники
  (Testcontainers)
- Приёмочный `ModerationApiIT` (Testcontainers, детерминированный
  классификатор): загрузка → POST /plants → job → обработка → APPROVED,
  файл публично виден чужим; негативная картинка → REJECTED,
  `GET /plants/{id}/moderation` — причина и retryUploadAllowed
- Тег `inference` — `OnnxPlantClassifierIT` (запуск при наличии файла модели):
  два реальных изображения в `src/test/resources/moderation/reference`
  (растение / не-растение) → настоящий ONNX-инференс; в обычный `verify` не
  входит (skip без модели, честное сообщение)
- media: тесты `loadContent` (байты и формат; storageKey не покидает границу)

## Документация

- ADR-009 «Автоматическая модерация: MobileNetV2 ONNX»
- Глоссарий: «Классификатор растений» (`PlantClassifier`), «Инференс»
- aggregates.md: защищающие тесты `ModerationJob`
- context-map.md: `MediaAssets.loadContent` в таблице взаимодействий
- README: раздел «Модерация (moderation)», инструкция по модели и
  inference-демо

## Границы итерации

Входит: контекст moderation целиком, правка `media.api` (+loadContent),
ONNX-адаптер + maven-download-plugin, детерминированный адаптер, ADR-009,
docs, inference-демо с тегом.

Не входит: перевод invitation в tournaments при решении — итерация 5;
уведомления — лаба №4; вынос inference в сервис — лаба №2.

Definition of done: зелёный `./mvnw verify` (unit + IT + ArchUnit + JaCoCo
≥70%); сквозной сценарий «загрузка → модерация → одобрение → публичность»
через HTTP; inference-демо на положительном/отрицательном изображении по
флагу.
