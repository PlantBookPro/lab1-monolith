# identity-service — внутренний контракт

Потребители (Feign): `media`, `plants`, `tournaments`, `feed`
(`IdentityDirectoryClient`). Контроллер:
`identity-service/.../adapter/in/web/InternalDirectoryController.java`.
Формат ошибок — `ApiError` (ADR-018).

## GET /internal/actors/{userId}

Разрешение актёра (роли из БД) для демо-идентификации (ADR-016).

```json
{ "userId": "0c9f0a25-7627-4c73-a302-4d3b0f1c9a11", "roles": ["USER"] }
```

Ошибки: `404 ACTOR_NOT_FOUND` — неизвестный/деактивированный userId.

## GET /internal/users/{userId}/profile

Публичный профиль (feed-карточки, турниры).

```json
{ "id": "0c9f0a25-...", "displayName": "Вова", "active": true }
```

Ошибки: `404 USER_NOT_FOUND`.

## GET /internal/users/{userId}/location

Координаты для гео-кластеризации (tournaments, ADR-012).

```json
{ "latitude": 55.75, "longitude": 37.61, "locationVersion": 3 }
```

Ошибки: `404 USER_NOT_FOUND` (в т.ч. профиль без локации).
