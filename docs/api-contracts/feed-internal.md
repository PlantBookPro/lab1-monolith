# feed-service — внутренний контракт

Потребитель (Feign): `tournaments` (`FeedEventsClient`). Контроллер:
`feed-service/.../adapter/in/web/InternalProjectionController.java`.

## POST /internal/feed/projections

Событие `VotingWindowOpened`: проекция карточек окна (ADR-002).
Идемпотентно по `windowId`.

```json
{
  "windowId": "w1d2...", "tournamentId": "t1...", "scope": "GLOBAL",
  "clusterId": "3a6d...", "closesAt": "2026-10-24T12:00:00Z",
  "cards": [
    { "entryId": "5b1c...", "userId": "0c9f...", "plantId": "9a3e...",
      "joinedAt": "2026-10-23T10:00:00Z" }
  ]
}
```

`scope` — `PRIVATE` | `GLOBAL`. `204 No Content`.

## DELETE /internal/feed/projections/{windowId}

Событие `VotingWindowClosed`: удаление карточек окна. Идемпотентно.
`204 No Content`.

Невалидное тело — `400 MALFORMED_BODY` (общий формат `ApiError`,
ADR-018).
