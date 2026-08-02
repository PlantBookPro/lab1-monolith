# ADR-008: Задействованность и публичная видимость файлов

Статус: принято (итерация 3).

## Контекст

Разделы 6 и 13 требований: файл «задействуется» растением при подаче заявки;
публично виден чужим только через одобренное растение; удаление задействованного
файла запрещено (409); архивация растения освобождает файл. plants — downstream
от media (раздел 4.3): media не может зависеть от plants (plants уже зависит от
media ради метаданных asset — иначе цикл).

## Решение

1. Задействованность (`AssetClaim`: `assetId` PK, `plantId`, `publiclyVisible`,
   `claimedAt`) хранится в **media** (таблица `media.asset_claim`), потому что
   media — upstream. plants командует через опубликованный контракт
   `media.api.MediaAssetClaims` (Customer–Supplier): подача заявки —
   `claim(assetId, plantId, false)`, одобрение модерации — `claim(..., true)`
   (upsert публичности), архивация — `release`. Все команды идемпотентны
   (повтор доставки безопасен).
2. Публичная видимость файла = его растение APPROVED и не архивировано.
   media не знает про модерацию: plants сообщает результат командой `claim`
   с флагом. `GET /api/v1/files/{id}`: владелец — всегда; чужие — только
   публично задействованный файл (иначе 404, скрыт приватностью).
3. Один файл — одно неархивированное растение. Два частных уникальных
   ограничения: PK `media.asset_claim` по `asset_id` и
   `plants.plant_active_asset_uidx ON plant(asset_id) WHERE archived_at IS NULL`.
   Удаление задействованного файла — 409 `ASSET_IN_USE`.
4. Межагрегатные транзакции — отступления от «одна транзакция — один агрегат»
   (раздел 12): подача заявки — `Plant` (plants) + `AssetClaim` (media) в одной
   транзакции монолита; гибель — `Plant` + `ImageRestriction` в plants.
   В лабе №2 — saga-команды с retry и компенсацией (release claim при неудачной
   подаче), в лабе №4 — outbox → Kafka. Идемпотентность команд уже обеспечена
   (upsert claim, no-op release, DEAD → no-op).

## Последствия

- media хранит `plantId`, не зная контекста plants: это ключ команды, не ссылка
  на агрегат; FK не создаётся (контексты выделяются в сервисы, раздел 10.4).
- Публичности файла «самой по себе» не существует: только через одобренное
  растение.
- Архивация растения — единственный штатный способ освободить файл; история
  растения, запреты и резервы при архивации сохраняются.
- В лабе №2 `InProcessMediaGateway` заменяется на Feign-адаптер с тем же портом
  `MediaAssetClaimsGateway`: меняется только адаптер.

## Файлы

- `media.api`: `MediaAssets`, `MediaAssetClaims`, `AssetInUseException`
- `media.domain.AssetClaim`; миграция `db/migration/media/V3__asset_claims.sql`
- `media.application`: `MediaAssetsFacade`, `MediaAssetClaimsFacade`
- `plants.adapter.out.media.InProcessMediaGateway` (ACL)
- `db/migration/plants/V2__plants.sql` (`plant_active_asset_uidx`)
- Тесты: `MediaAssetClaimsFacadeTest`, `MediaAssetServiceTest`
  (задействованность), `PlantServiceTest`, `PlantsApiIT` (одобрение раскрывает
  файл чужим; удаление занятого файла — 409; архивация освобождает)
