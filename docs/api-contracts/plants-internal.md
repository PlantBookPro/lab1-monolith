# plants-service — внутренний контракт

Потребители (Feign): `moderation` (`PlantsDirectoryClient`),
`tournaments` (`PlantsDirectoryClient`), `feed` (`PlantsDirectoryClient`).
Контроллер: `plants-service/.../adapter/in/web/InternalPlantController.java`.

## GET /internal/plants/{plantId}

Данные растения (tournaments: проверка при принятии; feed: публичные
данные карточки).

```json
{ "id": "9a3e...", "ownerId": "0c9f...", "assetId": "1f0e...",
  "title": "Фикус", "moderationStatus": "APPROVED", "lifeStatus": "ALIVE" }
```

Ошибки: `404 PLANT_NOT_FOUND`.

## GET /internal/plants?moderationStatus=PENDING&limit=50

PENDING-растения для модерации (polling, ADR-015). `moderationStatus` —
обязательный параметр, `limit` — по умолчанию 50.

```json
[ { "plantId": "9a3e...", "assetId": "1f0e..." } ]
```

## POST /internal/plants/{plantId}/moderation-decision

Решение модерации (moderation). Идемпотентность: повтор с тем же
решением — no-op.

```json
{ "decision": "APPROVED", "reason": "PLANT_DETECTED" }
```

Ответ — `PlantDataResponse` (как GET). Ошибки: `404 PLANT_NOT_FOUND`;
`409 MODERATION_ALREADY_DECIDED` — повтор с другим решением.

## POST /internal/plants/{plantId}/death

Гибель растения (tournaments: закрытие окна/эпохи; PERMANENT- или
COOLDOWN-запрет). Идемпотентна по DEAD-статусу.

```json
{ "kind": "PERMANENT", "cooldownExpiresAt": null,
  "reason": "ELIMINATED", "sourceEntryId": "5b1c..." }
```

`204 No Content`. Ошибки: `404 PLANT_NOT_FOUND`.

## POST /internal/plants/reservations

Резерв изображения под заявку (tournaments, saga: ADR-010/015).
`idempotencyKey` — свежий UUID на каждое принятие приглашения.

```json
{ "ownerId": "0c9f...", "plantId": "9a3e...",
  "idempotencyKey": "7d2a..." }
```

```json
{ "reservationId": "c4e8..." }
```

Ошибки: `409 PLANT_NOT_ELIGIBLE` (не APPROVED/не ALIVE, `retryAt`);
`409 IMAGE_RESTRICTED` (запрещённое изображение, `retryAt` — срок
суточного запрета); `409 RESERVATION_CONFLICT` (активный резерв уже
существует).

## POST /internal/plants/reservations/{reservationId}/confirm

Подтверждение резерва (старт турнира). `204 No Content`.

```json
{ "ownerId": "0c9f...", "plantId": "9a3e..." }
```

## POST /internal/plants/reservations/{reservationId}/release

Освобождение резерва (отмена, EXPIRED, выбывание, победа). `204 No
Content`; идемпотентен.
