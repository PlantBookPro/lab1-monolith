# media-service — внутренний контракт

Потребители (Feign): `plants` (`MediaDirectoryClient`), `moderation`
(`MediaContentClient`). Контроллер:
`media-service/.../adapter/in/web/InternalMediaController.java`.

## GET /internal/files/{assetId}/metadata

Метаданные asset (plants: проверка владельца/отпечатка; ADR-007/008).

```json
{ "id": "1f0e...", "ownerId": "0c9f...", "fingerprint": "a1b2c3d4",
  "fingerprintVersion": 1 }
```

Ошибки: `404 ASSET_NOT_FOUND`.

## GET /internal/files/{assetId}/content

Байты файла (moderation: инференс ONNX). Ответ — **не JSON**: тело —
сырые байты, `Content-Type` — фактический MIME (JPEG/PNG).

Ошибки: `404 ASSET_NOT_FOUND`.

## POST /internal/files/{assetId}/claims

Задействование файла растением + публичность (ADR-008). Вызывается
plants в tx подачи заявки; сбой — компенсация.

```json
{ "plantId": "9a3e...", "publiclyVisible": false }
```

`200` с пустым телом. Ошибки: `404 ASSET_NOT_FOUND`;
`409 ASSET_ALREADY_CLAIMED` — файл уже задействован другим
неархивированным растением.

## DELETE /internal/files/{assetId}/claims/{plantId}

Освобождение файла (архивация растения, освобождение резерва).
`200` с пустым телом; идемпотентен. Ошибки: `404 ASSET_NOT_FOUND`.
