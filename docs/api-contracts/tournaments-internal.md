# tournaments-service — внутренний контракт

Потребители (Feign): `plants` (`TournamentsEventsClient` — события),
`feed` (`TournamentsDirectoryClient` — read-порты). Контроллеры:
`tournaments-service/.../adapter/in/web/InternalEventsController.java`,
`InternalFeedDirectoryController.java`.

## POST /internal/events/plant-moderation-decided

Событие `PlantModerationDecided` от plants (AFTER_COMMIT-relay —
ADR-015). **Идемпотентная команда**: повтор — no-op по состоянию заявки
(перевод в READY / возврат в INVITED; после дедлайна/отмены — no-op с
сохранением истории).

```json
{ "plantId": "9a3e...", "decision": "APPROVED" }
```

`204 No Content`.

## GET /internal/feed/voted-entry-ids?subjectKey=...

Entry, уже оценённые субъектом в открытых окнах (исключение карточек из
ленты). `subjectKey` — `USER:<uuid>` или `GUEST:<sessionId>`.

```json
["5b1c...", "8f2e..."]
```

## GET /internal/feed/participated-tournament-ids?userId=...

Турниры участия пользователя (исключение своих карточек).

```json
["t1...", "t2..."]
```

## GET /internal/guest-sessions/active?token=...

Активная гостевая сессия по сырому токену (ADR-013; проверяет feed при
голосовании гостя и выдаче ленты).

```json
{ "sessionId": "g9d4..." }
```

Ошибки: `404 GUEST_SESSION_NOT_FOUND` — неизвестный/истёкший токен.
