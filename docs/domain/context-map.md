# Context Map — границы bounded contexts

Источник: `docs/requirements.md`, разделы 4.2–4.3. Направления зависимостей
проверяются ArchUnit-тестом `ContextBoundaryTest` (включая отдельное правило на
каждое запрещённое направление).

## Диаграмма контекстов (направления зависимостей)

```mermaid
graph TD
    identity[identity<br/>пользователи, роли, CurrentActor]
    media[media<br/>MediaAsset, отпечатки, FileStorage]
    geo[geo<br/>кластеризация, ClusterSnapshot]
    plants[plants<br/>Plant, запреты, резервы]
    moderation[moderation<br/>ModerationJob, классификатор]
    tournaments[tournaments<br/>турниры, окна, голоса, счёт]
    feed[feed<br/>лента, проекция карточек]

    plants -->|ACL: метаданные asset, команды задействованности| media
    moderation -->|ACL: PlantSubmitted / recordDecision| plants
    moderation -->|ACL: loadContent (байты)| media
    tournaments -->|ACL: CurrentActor, UserDirectory| identity
    tournaments -->|ACL: PlantEligibility, PlantDirectory, PlantLifecycle| plants
    tournaments -->|ACL: ClusteringGateway| geo
    feed -->|read-порты| tournaments
    feed -->|read-порты| plants
    feed -->|read-порты| media
    feed -->|read-порты| identity
```

## Допустимые зависимости (раздел 4.3 требований)

| Контекст | Может обращаться к `api` контекстов | Тип отношения |
|---|---|---|
| identity | — | Upstream для всех; Open Host Service (CurrentActor, публичный профиль, `UserDirectory` — итерация 5) |
| media | — | Upstream для plants, moderation, feed |
| geo | — | Upstream для tournaments; получает координаты во входной команде, сам identity не читает |
| plants | media | Customer–Supplier; ACL над `MediaAsset`, команды задействованности `MediaAssetClaims` (ADR-008) |
| moderation | plants, media | Downstream: подписан на `PlantSubmitted` (adapter.in.events), решение командой `plants.api.PlantModeration.recordDecision`; байты файла — `media.api.MediaAssets.loadContent` (ACL adapter.out.media) |
| tournaments | identity, plants, geo | Downstream; ACL `PlantEligibility`, `PlantDirectory`, `ParticipantDirectory`, `ClusteringGateway` (geo — итерация 7); подписан на `PlantModerationDecided` (adapter.in.events, та же tx — ADR-010); публикует `TournamentStarted`, `EntryEliminated`, `TournamentFinished` (в tx старта/закрытия окна — ADR-010/011) и `VotingWindowOpened`, `VotingWindowClosed` (в tx открытия/закрытия окна — проекция feed, итерация 8) |
| feed | tournaments, plants, media, identity | Downstream, только чтение через read-порты |

Правило: контекст использует **только пакет `api`** другого контекста и только
из своих адаптеров (`adapter.out.<ctx>` / `adapter.in.events`). `domain` и
`application` контекста не импортируют другие контексты вообще.

## Правила, устраняющие циклы (дословно из требований)

- plants не знает о moderation и tournaments. Когда создан `Plant`, plants
  публикует событие `PlantSubmitted`; moderation создаёт задание, подписавшись
  на него. Решение moderation передаёт командой `plants.api.PlantModeration.recordDecision(...)`.
- plants публикует `PlantModerationDecided` (APPROVED/REJECTED); tournaments
  подписан на него и переводит заявку (READY / возврат в INVITED).
- tournaments сообщает о гибели командой `plants.api.PlantLifecycle.registerDeath(...)`,
  а не записью в таблицы plants.
- Потребитель владеет своим портом (Consumer-driven): например,
  `tournaments.application.port.out.PlantEligibility` с собственными типами
  ответа; адаптер `tournaments.adapter.out.plants.InProcessPlantEligibility`
  вызывает `plants.api` и переводит его DTO в модель tournaments (ACL).
  В лабе №2 меняется только этот адаптер.
- plants сообщает media о задействованности и публичности файла командами
  `media.api.MediaAssetClaims.claim/release`; media хранит задействованность,
  но не знает о растениях (ADR-008) — plantId это ключ команды, не ссылка.

## Таблица взаимодействий

| Инициатор | Получатель | Взаимодействие | Тип | Синхронность | Лаба №2 | Лаба №4 |
|---|---|---|---|---|---|---|
| plants | media | метаданные `MediaAsset` по assetId | запрос через `media.api` | синхронно | Feign | Feign |
| plants | media | `MediaAssetClaims.claim/release` (задействованность, публичность) | команда | синхронно, в транзакции подачи/архивации | Feign + компенсация | Kafka-команды |
| moderation | plants | подписка на `PlantSubmitted` | событие | in-process | идемпотентная HTTP-команда + retry | Kafka `plant.moderation.v1` |
| moderation | plants | `PlantModeration.recordDecision` | команда | синхронно | HTTP-команда | Kafka |
| moderation | media | метаданные asset для инференса | запрос через `media.api` | синхронно | Feign | Feign |
| tournaments | identity | `CurrentActor`, публичный профиль | запрос | синхронно | Feign | Feign |
| tournaments | plants | `PlantEligibility.reserveSubmission/confirmEligibility` | команда/запрос | синхронно | Feign + saga | Kafka-команды |
| tournaments | plants | `PlantDirectory.findById` (read, проверка APPROVED при принятии) | запрос через `plants.api` | синхронно | Feign | Feign |
| tournaments | identity | `UserDirectory.findById` (известный активный пользователь) | запрос через `identity.api` | синхронно | Feign | Feign |
| tournaments | identity | `UserDirectory.findLocation` (координаты для кластеризации, ACL `ParticipantLocationsGateway`) | запрос через `identity.api` | синхронно | Feign | Feign |
| plants | tournaments | публикация `PlantModerationDecided` (перевод заявки) | событие | синхронно, в tx решения (ADR-010) | outbox → идемпотентная команда | Kafka `plant.moderation.v1` |
| tournaments | plants | `PlantLifecycle.registerDeath` (закрытие окна: гибель выбывшего + PERMANENT-запрет, устойчивый порядок по plantId) | команда | синхронно, в транзакции закрытия окна (ADR-011) | надёжная команда с повтором (идемпотентность — DEAD-статус plants) | Kafka `plant.lifecycle.v1` |
| tournaments | geo | `ClusteringGateway.assignClusters` (кластеризация состава эпохи + неизменные снимки, ADR-012) | команда | синхронно, в транзакции открытия эпохи | Feign + идемпотентный повтор по epochId | Kafka-команды |
| scheduler (tournaments) | `AdvanceGlobalCompetitionUseCase` | границы глобального режима (закрытие квалификации/финала, открытие финала/эпохи — фиксированный порядок, ADR-012) | вызов use case (fixedDelay 2с, идемпотентен) | in-process | scheduler сервиса | scheduler сервиса |
| feed | tournaments | проекция карточек по событиям `VotingWindowOpened`/`VotingWindowClosed` (создание/удаление карточек окна); read `FeedDirectory` (оцененные entry, турниры участия), `GuestSessionDirectory` (активная сессия по токену) | события + read-порты | синхронно in-process (та же tx — ADR-011) | события внутри tournament-service | outbox → Kafka → проекция |
| feed | plants | публичные данные растения | запрос через `plants.api` | синхронно | Feign | Feign |
| feed | media | URL изображения по assetId | запрос через `media.api` | синхронно | Feign | Feign |
| feed | identity | минимальные публичные сведения о владельце | запрос через `identity.api` | синхронно | Feign | Feign |

## Способ выполнения обязательных последствий (раздел 10.3)

- Одобрение модерации → перевод заявки в READY: синхронно в той же транзакции
  координирующим use case применения решения модерации.
- Гибель растений при закрытии окна: синхронно в той же транзакции закрытия окна
  через команды `plants.api`.
- `@TransactionalEventListener(AFTER_COMMIT)` и `@Async` — только для
  необязательных побочных эффектов (уведомления, журнал), никогда для инвариантов.
