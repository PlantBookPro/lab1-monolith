# Happy-path ревью API — Plant Arena (лаба №1)

**Дата:** 2026-10-04 20:18  
**Окружение:** `http://localhost:8080/api/v1`, профиль dev, демо-идентификация `X-Demo-User-Id` (ADR-005).  
**Параметры:** дедлайн регистрации +150 с, раунд 15 с, доля выбывания 0.5, минимум 2 участника.

Сценарий: создание модератора → приватный турнир → 3 участника → регистрация в глобальный турнир (автоматическая модерация ONNX) → приглашения и регистрация в приватный турнир → старт → 2 раунда голосования с выбыванием → результаты. В раунде 1 выбывает p3, в раунде 2 — p2, победитель — p1.

Каждому участнику нужно **2 разных растения**: пара (owner, fingerprint) допускает только один активный резерв (допущение 4, ADR-010) — одно изображение уходит в глобальный турнир, другое в приватный.

## 1. Модератор

### 1.1 Создание пользователя-модератора (админ)

```http
POST /users
X-Demo-User-Id: 02d6f959-4ab2-457a-ace4-38f949459665
{"email":"review-mod-1791134296@plantarena.local","password":"password-9","displayName":"Review Moderator"}
```

**Ответ: `201`**

```json
{
  "id": "676a2ad0-2367-4248-b88d-45f78d3fc83d",
  "email": "review-mod-1791134296@plantarena.local",
  "displayName": "Review Moderator",
  "roles": [
    "USER"
  ],
  "status": "ACTIVE",
  "latitude": null,
  "longitude": null
}
```

### 1.2 Назначение роли модератора (админ, идемпотентно)

```http
PUT /users/676a2ad0-2367-4248-b88d-45f78d3fc83d/roles/moderator
X-Demo-User-Id: 02d6f959-4ab2-457a-ace4-38f949459665
```

**Ответ: `200`**

```json
{
  "id": "676a2ad0-2367-4248-b88d-45f78d3fc83d",
  "email": "review-mod-1791134296@plantarena.local",
  "displayName": "Review Moderator",
  "roles": [
    "USER",
    "MODERATOR"
  ],
  "status": "ACTIVE",
  "latitude": null,
  "longitude": null
}
```

## 2. Участники (создаёт модератор)

### 2.1 Создание участника p1 (модератор)

```http
POST /users
X-Demo-User-Id: 676a2ad0-2367-4248-b88d-45f78d3fc83d
{"email":"review-p1-1791134296@plantarena.local","password":"password-9","displayName":"Participant 1"}
```

**Ответ: `201`**

```json
{
  "id": "06030188-5dee-4fb3-b31b-1ada1c5345a1",
  "email": "review-p1-1791134296@plantarena.local",
  "displayName": "Participant 1",
  "roles": [
    "USER"
  ],
  "status": "ACTIVE",
  "latitude": null,
  "longitude": null
}
```

### 2.2 Создание участника p2 (модератор)

```http
POST /users
X-Demo-User-Id: 676a2ad0-2367-4248-b88d-45f78d3fc83d
{"email":"review-p2-1791134296@plantarena.local","password":"password-9","displayName":"Participant 2"}
```

**Ответ: `201`**

```json
{
  "id": "8274ebc2-6510-4c01-9f6b-39a71ebd1360",
  "email": "review-p2-1791134296@plantarena.local",
  "displayName": "Participant 2",
  "roles": [
    "USER"
  ],
  "status": "ACTIVE",
  "latitude": null,
  "longitude": null
}
```

### 2.3 Создание участника p3 (модератор)

```http
POST /users
X-Demo-User-Id: 676a2ad0-2367-4248-b88d-45f78d3fc83d
{"email":"review-p3-1791134296@plantarena.local","password":"password-9","displayName":"Participant 3"}
```

**Ответ: `201`**

```json
{
  "id": "2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1",
  "email": "review-p3-1791134296@plantarena.local",
  "displayName": "Participant 3",
  "roles": [
    "USER"
  ],
  "status": "ACTIVE",
  "latitude": null,
  "longitude": null
}
```

## 3. Профили: координаты (обязательны для глобального турнира)

### 3.1 p1 задаёт локацию

```http
PUT /me/location
X-Demo-User-Id: 06030188-5dee-4fb3-b31b-1ada1c5345a1
{"latitude":55.7560,"longitude":37.6180}
```

**Ответ: `200`**

```json
{
  "id": "06030188-5dee-4fb3-b31b-1ada1c5345a1",
  "email": "review-p1-1791134296@plantarena.local",
  "displayName": "Participant 1",
  "roles": [
    "USER"
  ],
  "status": "ACTIVE",
  "latitude": 55.756,
  "longitude": 37.618
}
```

### 3.2 p2 задаёт локацию

```http
PUT /me/location
X-Demo-User-Id: 8274ebc2-6510-4c01-9f6b-39a71ebd1360
{"latitude":59.9390,"longitude":30.3150}
```

**Ответ: `200`**

```json
{
  "id": "8274ebc2-6510-4c01-9f6b-39a71ebd1360",
  "email": "review-p2-1791134296@plantarena.local",
  "displayName": "Participant 2",
  "roles": [
    "USER"
  ],
  "status": "ACTIVE",
  "latitude": 59.939,
  "longitude": 30.315
}
```

### 3.3 p3 задаёт локацию

```http
PUT /me/location
X-Demo-User-Id: 2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1
{"latitude":59.9395,"longitude":30.3160}
```

**Ответ: `200`**

```json
{
  "id": "2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1",
  "email": "review-p3-1791134296@plantarena.local",
  "displayName": "Participant 3",
  "roles": [
    "USER"
  ],
  "status": "ACTIVE",
  "latitude": 59.9395,
  "longitude": 30.316
}
```

## 4. Растения для глобального турнира: загрузка → заявка → автоматическая модерация

Изображения — разные кропы эталонной ромашки (daisy.jpg, ADR-009): классификатор MobileNetV2 ONNX должен распознать растение. Задания модерации выполняются scheduler'ом автоматически (2 с), без ручного запуска.

### 4.1 p1 загружает изображение (multipart)

```http
POST /files
X-Demo-User-Id: 06030188-5dee-4fb3-b31b-1ada1c5345a1
Content-Type: multipart/form-data; file=p1a.jpg
```

**Ответ: `201`**

```json
{
  "id": "b4a9ea82-2817-4e7c-9281-5ce074bb93d3",
  "ownerId": "06030188-5dee-4fb3-b31b-1ada1c5345a1",
  "mimeType": "image/jpeg",
  "byteSize": 14950,
  "width": 210,
  "height": 190,
  "createdAt": "2026-10-04T17:18:17.444265805Z"
}
```

### 4.2 p1 подаёт растение

```http
POST /plants
X-Demo-User-Id: 06030188-5dee-4fb3-b31b-1ada1c5345a1
{"assetId":"b4a9ea82-2817-4e7c-9281-5ce074bb93d3","title":"Ромашка p1 (global)"}
```

**Ответ: `201`**

```json
{
  "id": "9ff74c63-38a2-463a-b192-dcbde0d0d67a",
  "ownerId": "06030188-5dee-4fb3-b31b-1ada1c5345a1",
  "assetId": "b4a9ea82-2817-4e7c-9281-5ce074bb93d3",
  "title": "Ромашка p1 (global)",
  "moderationStatus": "PENDING",
  "lifeStatus": "ALIVE",
  "createdAt": "2026-10-04T17:18:17.491308513Z",
  "diedAt": null,
  "archivedAt": null
}
```

### 4.3 p1 проверяет решение модерации (после автоматической обработки scheduler'ом)

```http
GET /plants/9ff74c63-38a2-463a-b192-dcbde0d0d67a/moderation
X-Demo-User-Id: 06030188-5dee-4fb3-b31b-1ada1c5345a1
```

**Ответ: `200`**

```json
{
  "moderationStatus": "APPROVED",
  "reason": "PLANT_DETECTED",
  "retryUploadAllowed": false
}
```

### 4.4 p2 загружает изображение (multipart)

```http
POST /files
X-Demo-User-Id: 8274ebc2-6510-4c01-9f6b-39a71ebd1360
Content-Type: multipart/form-data; file=p2a.jpg
```

**Ответ: `201`**

```json
{
  "id": "83bcd20e-16e9-463d-a082-449087bb388d",
  "ownerId": "8274ebc2-6510-4c01-9f6b-39a71ebd1360",
  "mimeType": "image/jpeg",
  "byteSize": 13828,
  "width": 200,
  "height": 180,
  "createdAt": "2026-10-04T17:18:19.671780333Z"
}
```

### 4.5 p2 подаёт растение

```http
POST /plants
X-Demo-User-Id: 8274ebc2-6510-4c01-9f6b-39a71ebd1360
{"assetId":"83bcd20e-16e9-463d-a082-449087bb388d","title":"Ромашка p2 (global)"}
```

**Ответ: `201`**

```json
{
  "id": "c038ccba-7bc8-48f7-ae90-7002e87c1366",
  "ownerId": "8274ebc2-6510-4c01-9f6b-39a71ebd1360",
  "assetId": "83bcd20e-16e9-463d-a082-449087bb388d",
  "title": "Ромашка p2 (global)",
  "moderationStatus": "PENDING",
  "lifeStatus": "ALIVE",
  "createdAt": "2026-10-04T17:18:19.720019083Z",
  "diedAt": null,
  "archivedAt": null
}
```

### 4.6 p2 проверяет решение модерации (после автоматической обработки scheduler'ом)

```http
GET /plants/c038ccba-7bc8-48f7-ae90-7002e87c1366/moderation
X-Demo-User-Id: 8274ebc2-6510-4c01-9f6b-39a71ebd1360
```

**Ответ: `200`**

```json
{
  "moderationStatus": "APPROVED",
  "reason": "PLANT_DETECTED",
  "retryUploadAllowed": false
}
```

### 4.7 p3 загружает изображение (multipart)

```http
POST /files
X-Demo-User-Id: 2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1
Content-Type: multipart/form-data; file=p3a.jpg
```

**Ответ: `201`**

```json
{
  "id": "d9cc6c09-8021-4c24-b7b0-1c2f4bbee2e5",
  "ownerId": "2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1",
  "mimeType": "image/jpeg",
  "byteSize": 11410,
  "width": 185,
  "height": 170,
  "createdAt": "2026-10-04T17:18:21.893569376Z"
}
```

### 4.8 p3 подаёт растение

```http
POST /plants
X-Demo-User-Id: 2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1
{"assetId":"d9cc6c09-8021-4c24-b7b0-1c2f4bbee2e5","title":"Ромашка p3 (global)"}
```

**Ответ: `201`**

```json
{
  "id": "388b1f25-43d9-492e-a2bd-beb27a692ad5",
  "ownerId": "2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1",
  "assetId": "d9cc6c09-8021-4c24-b7b0-1c2f4bbee2e5",
  "title": "Ромашка p3 (global)",
  "moderationStatus": "PENDING",
  "lifeStatus": "ALIVE",
  "createdAt": "2026-10-04T17:18:21.943106709Z",
  "diedAt": null,
  "archivedAt": null
}
```

### 4.9 p3 проверяет решение модерации (после автоматической обработки scheduler'ом)

```http
GET /plants/388b1f25-43d9-492e-a2bd-beb27a692ad5/moderation
X-Demo-User-Id: 2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1
```

**Ответ: `200`**

```json
{
  "moderationStatus": "APPROVED",
  "reason": "PLANT_DETECTED",
  "retryUploadAllowed": false
}
```

## 5. Регистрация в глобальный турнир (автоматом, без приглашений)

### 5.0 Конфигурация глобального турнира (публично)

```http
GET /global
```

**Ответ: `200`**

```json
{
  "epochDurationSeconds": 86400,
  "finalWindowDurationSeconds": 21600,
  "currentEpoch": {
    "id": "94ab1588-6133-4875-bf54-6752cca3cc8b",
    "sequence": 1,
    "opensAt": "2026-10-04T16:46:57.023016Z",
    "closesAt": "2026-10-05T16:46:57.023016Z"
  },
  "currentFinalWindow": null
}
```


Подача заявки — без приглашений и без модератора: своё APPROVED-растение → сразу в очередь (QUEUED). Резерв изображения создаётся автоматически.

### 5.1 p1 подаёт заявку в глобальный турнир

```http
POST /global/entries
X-Demo-User-Id: 06030188-5dee-4fb3-b31b-1ada1c5345a1
{"plantId":"9ff74c63-38a2-463a-b192-dcbde0d0d67a"}
```

**Ответ: `201`**

```json
{
  "id": "5f561940-4fe0-42be-845d-2f342026ecce",
  "plantId": "9ff74c63-38a2-463a-b192-dcbde0d0d67a",
  "status": "QUEUED",
  "joinedAt": "2026-10-04T17:18:24.153066627Z"
}
```

### 5.11 p1 проверяет своё участие

```http
GET /me/global-entry
X-Demo-User-Id: 06030188-5dee-4fb3-b31b-1ada1c5345a1
```

**Ответ: `200`**

```json
{
  "id": "5f561940-4fe0-42be-845d-2f342026ecce",
  "plantId": "9ff74c63-38a2-463a-b192-dcbde0d0d67a",
  "status": "QUEUED",
  "joinedAt": "2026-10-04T17:18:24.153067Z"
}
```

### 5.2 p2 подаёт заявку в глобальный турнир

```http
POST /global/entries
X-Demo-User-Id: 8274ebc2-6510-4c01-9f6b-39a71ebd1360
{"plantId":"c038ccba-7bc8-48f7-ae90-7002e87c1366"}
```

**Ответ: `201`**

```json
{
  "id": "7b6814c9-25fd-488d-84e2-16409e14de2b",
  "plantId": "c038ccba-7bc8-48f7-ae90-7002e87c1366",
  "status": "QUEUED",
  "joinedAt": "2026-10-04T17:18:24.238340960Z"
}
```

### 5.12 p2 проверяет своё участие

```http
GET /me/global-entry
X-Demo-User-Id: 8274ebc2-6510-4c01-9f6b-39a71ebd1360
```

**Ответ: `200`**

```json
{
  "id": "7b6814c9-25fd-488d-84e2-16409e14de2b",
  "plantId": "c038ccba-7bc8-48f7-ae90-7002e87c1366",
  "status": "QUEUED",
  "joinedAt": "2026-10-04T17:18:24.238341Z"
}
```

### 5.3 p3 подаёт заявку в глобальный турнир

```http
POST /global/entries
X-Demo-User-Id: 2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1
{"plantId":"388b1f25-43d9-492e-a2bd-beb27a692ad5"}
```

**Ответ: `201`**

```json
{
  "id": "5042601f-07fc-44e4-82d4-220e232ff9e7",
  "plantId": "388b1f25-43d9-492e-a2bd-beb27a692ad5",
  "status": "QUEUED",
  "joinedAt": "2026-10-04T17:18:24.325077294Z"
}
```

### 5.13 p3 проверяет своё участие

```http
GET /me/global-entry
X-Demo-User-Id: 2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1
```

**Ответ: `200`**

```json
{
  "id": "5042601f-07fc-44e4-82d4-220e232ff9e7",
  "plantId": "388b1f25-43d9-492e-a2bd-beb27a692ad5",
  "status": "QUEUED",
  "joinedAt": "2026-10-04T17:18:24.325077Z"
}
```

## 6. Приватный турнир (создаёт модератор)

### 6.1 Модератор создаёт тег

```http
POST /tags
X-Demo-User-Id: 676a2ad0-2367-4248-b88d-45f78d3fc83d
{"name":"review-happy-path-1791134296"}
```

**Ответ: `201`**

```json
{
  "id": "23a08e7b-f243-4255-bb05-d775132ca4d1",
  "name": "review-happy-path-1791134296"
}
```

### 6.2 Модератор создаёт турнир (DRAFT): дедлайн 2026-10-04T17:20:54Z, раунд 15 с, доля выбывания 0.5, минимум 2

```http
POST /tournaments
X-Demo-User-Id: 676a2ad0-2367-4248-b88d-45f78d3fc83d
{"name":"Review Happy Path Cup","description":"Ревью happy path: 3 участника, 2 раунда","registrationDeadline":"2026-10-04T17:20:54Z","roundDurationSeconds":15,"eliminationFraction":0.5,"minParticipants":2,"tagIds":["23a08e7b-f243-4255-bb05-d775132ca4d1"]}
```

**Ответ: `201`**

```json
{
  "id": "9c25b3e8-d905-44b2-9b21-a4868730f854",
  "creatorId": "676a2ad0-2367-4248-b88d-45f78d3fc83d",
  "name": "Review Happy Path Cup",
  "description": "Ревью happy path: 3 участника, 2 раунда",
  "type": "PRIVATE",
  "status": "DRAFT",
  "algorithm": "ROUND_ELIMINATION",
  "registrationDeadline": "2026-10-04T17:20:54Z",
  "roundDurationSeconds": 15,
  "eliminationFraction": 0.5,
  "minParticipants": 2,
  "cancelReason": null,
  "tagIds": [
    "23a08e7b-f243-4255-bb05-d775132ca4d1"
  ],
  "createdAt": "2026-10-04T17:18:24.508922335Z"
}
```

### 6.3 Открытие регистрации

```http
POST /tournaments/9c25b3e8-d905-44b2-9b21-a4868730f854/open-registration
X-Demo-User-Id: 676a2ad0-2367-4248-b88d-45f78d3fc83d
```

**Ответ: `200`**

```json
{
  "id": "9c25b3e8-d905-44b2-9b21-a4868730f854",
  "creatorId": "676a2ad0-2367-4248-b88d-45f78d3fc83d",
  "name": "Review Happy Path Cup",
  "description": "Ревью happy path: 3 участника, 2 раунда",
  "type": "PRIVATE",
  "status": "REGISTRATION_OPEN",
  "algorithm": "ROUND_ELIMINATION",
  "registrationDeadline": "2026-10-04T17:20:54Z",
  "roundDurationSeconds": 15,
  "eliminationFraction": 0.5,
  "minParticipants": 2,
  "cancelReason": null,
  "tagIds": [
    "23a08e7b-f243-4255-bb05-d775132ca4d1"
  ],
  "createdAt": "2026-10-04T17:18:24.508922Z"
}
```

### 6.4 Модератор приглашает p1

```http
POST /tournaments/9c25b3e8-d905-44b2-9b21-a4868730f854/invitations
X-Demo-User-Id: 676a2ad0-2367-4248-b88d-45f78d3fc83d
{"userId":"06030188-5dee-4fb3-b31b-1ada1c5345a1"}
```

**Ответ: `201`**

```json
{
  "id": "8f776d5a-005e-4efd-8ea6-e13e0b6563aa",
  "tournamentId": "9c25b3e8-d905-44b2-9b21-a4868730f854",
  "userId": "06030188-5dee-4fb3-b31b-1ada1c5345a1",
  "status": "INVITED",
  "invitedAt": "2026-10-04T17:18:24.610462794Z",
  "respondedAt": null,
  "submittedPlantId": null
}
```

### 6.5 Модератор приглашает p2

```http
POST /tournaments/9c25b3e8-d905-44b2-9b21-a4868730f854/invitations
X-Demo-User-Id: 676a2ad0-2367-4248-b88d-45f78d3fc83d
{"userId":"8274ebc2-6510-4c01-9f6b-39a71ebd1360"}
```

**Ответ: `201`**

```json
{
  "id": "d8307c40-188b-45ed-b789-fa14b8b26739",
  "tournamentId": "9c25b3e8-d905-44b2-9b21-a4868730f854",
  "userId": "8274ebc2-6510-4c01-9f6b-39a71ebd1360",
  "status": "INVITED",
  "invitedAt": "2026-10-04T17:18:24.647464877Z",
  "respondedAt": null,
  "submittedPlantId": null
}
```

### 6.6 Модератор приглашает p3

```http
POST /tournaments/9c25b3e8-d905-44b2-9b21-a4868730f854/invitations
X-Demo-User-Id: 676a2ad0-2367-4248-b88d-45f78d3fc83d
{"userId":"2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1"}
```

**Ответ: `201`**

```json
{
  "id": "285e002a-7878-469e-ae4a-f802077b5b02",
  "tournamentId": "9c25b3e8-d905-44b2-9b21-a4868730f854",
  "userId": "2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1",
  "status": "INVITED",
  "invitedAt": "2026-10-04T17:18:24.686823502Z",
  "respondedAt": null,
  "submittedPlantId": null
}
```

## 7. Растения для приватного турнира + принятие приглашений

Второе растение на участника — другой кроп (другой fingerprint): пара (owner, fingerprint) не может иметь два активных резерва.

### 7.1 p1 загружает второе изображение

```http
POST /files
X-Demo-User-Id: 06030188-5dee-4fb3-b31b-1ada1c5345a1
Content-Type: multipart/form-data; file=p1b.jpg
```

**Ответ: `201`**

```json
{
  "id": "70b61384-8e39-4b93-a90d-d226ffcfdd19",
  "ownerId": "06030188-5dee-4fb3-b31b-1ada1c5345a1",
  "mimeType": "image/jpeg",
  "byteSize": 14928,
  "width": 195,
  "height": 185,
  "createdAt": "2026-10-04T17:18:24.726083419Z"
}
```

### 7.2 p1 подаёт второе растение

```http
POST /plants
X-Demo-User-Id: 06030188-5dee-4fb3-b31b-1ada1c5345a1
{"assetId":"70b61384-8e39-4b93-a90d-d226ffcfdd19","title":"Ромашка p1 (private)"}
```

**Ответ: `201`**

```json
{
  "id": "b24eaefb-636a-40f7-bf5b-7e18dc92043b",
  "ownerId": "06030188-5dee-4fb3-b31b-1ada1c5345a1",
  "assetId": "70b61384-8e39-4b93-a90d-d226ffcfdd19",
  "title": "Ромашка p1 (private)",
  "moderationStatus": "PENDING",
  "lifeStatus": "ALIVE",
  "createdAt": "2026-10-04T17:18:24.772075960Z",
  "diedAt": null,
  "archivedAt": null
}
```

### 7.3 p1 смотрит свои приглашения

```http
GET /me/invitations
X-Demo-User-Id: 06030188-5dee-4fb3-b31b-1ada1c5345a1
```

**Ответ: `200`**

```json
[
  {
    "id": "8f776d5a-005e-4efd-8ea6-e13e0b6563aa",
    "tournamentId": "9c25b3e8-d905-44b2-9b21-a4868730f854",
    "userId": "06030188-5dee-4fb3-b31b-1ada1c5345a1",
    "status": "INVITED",
    "invitedAt": "2026-10-04T17:18:24.610463Z",
    "respondedAt": null,
    "submittedPlantId": null
  }
]
```

### 7.4 p1 принимает приглашение с растением (private)

```http
POST /invitations/8f776d5a-005e-4efd-8ea6-e13e0b6563aa/accept
X-Demo-User-Id: 06030188-5dee-4fb3-b31b-1ada1c5345a1
{"plantId":"b24eaefb-636a-40f7-bf5b-7e18dc92043b"}
```

**Ответ: `200`**

```json
{
  "id": "8f776d5a-005e-4efd-8ea6-e13e0b6563aa",
  "tournamentId": "9c25b3e8-d905-44b2-9b21-a4868730f854",
  "userId": "06030188-5dee-4fb3-b31b-1ada1c5345a1",
  "status": "READY",
  "invitedAt": "2026-10-04T17:18:24.610463Z",
  "respondedAt": "2026-10-04T17:18:26.935319295Z",
  "submittedPlantId": "b24eaefb-636a-40f7-bf5b-7e18dc92043b"
}
```

### 7.5 p2 загружает второе изображение

```http
POST /files
X-Demo-User-Id: 8274ebc2-6510-4c01-9f6b-39a71ebd1360
Content-Type: multipart/form-data; file=p2b.jpg
```

**Ответ: `201`**

```json
{
  "id": "59b9a72e-0ea5-4498-8695-0608e169c380",
  "ownerId": "8274ebc2-6510-4c01-9f6b-39a71ebd1360",
  "mimeType": "image/jpeg",
  "byteSize": 13529,
  "width": 190,
  "height": 175,
  "createdAt": "2026-10-04T17:18:26.981564378Z"
}
```

### 7.6 p2 подаёт второе растение

```http
POST /plants
X-Demo-User-Id: 8274ebc2-6510-4c01-9f6b-39a71ebd1360
{"assetId":"59b9a72e-0ea5-4498-8695-0608e169c380","title":"Ромашка p2 (private)"}
```

**Ответ: `201`**

```json
{
  "id": "a4f69009-4301-49ea-9844-fe77e4645eaa",
  "ownerId": "8274ebc2-6510-4c01-9f6b-39a71ebd1360",
  "assetId": "59b9a72e-0ea5-4498-8695-0608e169c380",
  "title": "Ромашка p2 (private)",
  "moderationStatus": "PENDING",
  "lifeStatus": "ALIVE",
  "createdAt": "2026-10-04T17:18:27.028018087Z",
  "diedAt": null,
  "archivedAt": null
}
```

### 7.7 p2 смотрит свои приглашения

```http
GET /me/invitations
X-Demo-User-Id: 8274ebc2-6510-4c01-9f6b-39a71ebd1360
```

**Ответ: `200`**

```json
[
  {
    "id": "d8307c40-188b-45ed-b789-fa14b8b26739",
    "tournamentId": "9c25b3e8-d905-44b2-9b21-a4868730f854",
    "userId": "8274ebc2-6510-4c01-9f6b-39a71ebd1360",
    "status": "INVITED",
    "invitedAt": "2026-10-04T17:18:24.647465Z",
    "respondedAt": null,
    "submittedPlantId": null
  }
]
```

### 7.8 p2 принимает приглашение с растением (private)

```http
POST /invitations/d8307c40-188b-45ed-b789-fa14b8b26739/accept
X-Demo-User-Id: 8274ebc2-6510-4c01-9f6b-39a71ebd1360
{"plantId":"a4f69009-4301-49ea-9844-fe77e4645eaa"}
```

**Ответ: `200`**

```json
{
  "id": "d8307c40-188b-45ed-b789-fa14b8b26739",
  "tournamentId": "9c25b3e8-d905-44b2-9b21-a4868730f854",
  "userId": "8274ebc2-6510-4c01-9f6b-39a71ebd1360",
  "status": "READY",
  "invitedAt": "2026-10-04T17:18:24.647465Z",
  "respondedAt": "2026-10-04T17:18:29.192077754Z",
  "submittedPlantId": "a4f69009-4301-49ea-9844-fe77e4645eaa"
}
```

### 7.9 p3 загружает второе изображение

```http
POST /files
X-Demo-User-Id: 2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1
Content-Type: multipart/form-data; file=p3b.jpg
```

**Ответ: `201`**

```json
{
  "id": "d1b0049b-2803-4ce1-84a0-e738d0008f41",
  "ownerId": "2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1",
  "mimeType": "image/jpeg",
  "byteSize": 12801,
  "width": 180,
  "height": 165,
  "createdAt": "2026-10-04T17:18:29.239464296Z"
}
```

### 7.10 p3 подаёт второе растение

```http
POST /plants
X-Demo-User-Id: 2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1
{"assetId":"d1b0049b-2803-4ce1-84a0-e738d0008f41","title":"Ромашка p3 (private)"}
```

**Ответ: `201`**

```json
{
  "id": "c5f7c9b4-880b-4116-a22f-3523859496dd",
  "ownerId": "2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1",
  "assetId": "d1b0049b-2803-4ce1-84a0-e738d0008f41",
  "title": "Ромашка p3 (private)",
  "moderationStatus": "PENDING",
  "lifeStatus": "ALIVE",
  "createdAt": "2026-10-04T17:18:29.284545963Z",
  "diedAt": null,
  "archivedAt": null
}
```

### 7.11 p3 смотрит свои приглашения

```http
GET /me/invitations
X-Demo-User-Id: 2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1
```

**Ответ: `200`**

```json
[
  {
    "id": "285e002a-7878-469e-ae4a-f802077b5b02",
    "tournamentId": "9c25b3e8-d905-44b2-9b21-a4868730f854",
    "userId": "2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1",
    "status": "INVITED",
    "invitedAt": "2026-10-04T17:18:24.686824Z",
    "respondedAt": null,
    "submittedPlantId": null
  }
]
```

### 7.12 p3 принимает приглашение с растением (private)

```http
POST /invitations/285e002a-7878-469e-ae4a-f802077b5b02/accept
X-Demo-User-Id: 2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1
{"plantId":"c5f7c9b4-880b-4116-a22f-3523859496dd"}
```

**Ответ: `200`**

```json
{
  "id": "285e002a-7878-469e-ae4a-f802077b5b02",
  "tournamentId": "9c25b3e8-d905-44b2-9b21-a4868730f854",
  "userId": "2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1",
  "status": "READY",
  "invitedAt": "2026-10-04T17:18:24.686824Z",
  "respondedAt": "2026-10-04T17:18:31.444083714Z",
  "submittedPlantId": "c5f7c9b4-880b-4116-a22f-3523859496dd"
}
```

## 8. Старт турнира

Дедлайн регистрации наступает — старт выполняет scheduler (fixedDelay 2 с); demo-ручка `run-due` вызывает тот же use case (идемпотентно, processed=0 если scheduler уже успел).

### 8.1 Продвижение наступивших дедлайнов (админ; тот же use case, что scheduler)

```http
POST /internal/demo/jobs/run-due
X-Demo-User-Id: 02d6f959-4ab2-457a-ace4-38f949459665
```

**Ответ: `200`**

```json
{
  "globalFinalsOpened": 0,
  "globalEpochsOpened": 0,
  "globalQualificationClosed": 0,
  "processed": 0,
  "globalFinalClosed": 0,
  "closedWindows": 0
}
```

### 8.2 Статус турнира

```http
GET /tournaments/9c25b3e8-d905-44b2-9b21-a4868730f854
X-Demo-User-Id: 676a2ad0-2367-4248-b88d-45f78d3fc83d
```

**Ответ: `200`**

```json
{
  "id": "9c25b3e8-d905-44b2-9b21-a4868730f854",
  "creatorId": "676a2ad0-2367-4248-b88d-45f78d3fc83d",
  "name": "Review Happy Path Cup",
  "description": "Ревью happy path: 3 участника, 2 раунда",
  "type": "PRIVATE",
  "status": "RUNNING",
  "algorithm": "ROUND_ELIMINATION",
  "registrationDeadline": "2026-10-04T17:20:54Z",
  "roundDurationSeconds": 15,
  "eliminationFraction": 0.5,
  "minParticipants": 2,
  "cancelReason": null,
  "tagIds": [
    "23a08e7b-f243-4255-bb05-d775132ca4d1"
  ],
  "createdAt": "2026-10-04T17:18:24.508922Z"
}
```

### 8.3 Участники турнира

```http
GET /tournaments/9c25b3e8-d905-44b2-9b21-a4868730f854/entries
X-Demo-User-Id: 676a2ad0-2367-4248-b88d-45f78d3fc83d
```

**Ответ: `200`**

```json
[
  {
    "id": "d2d61198-7212-494d-b05f-9a27b68423a0",
    "tournamentId": "9c25b3e8-d905-44b2-9b21-a4868730f854",
    "userId": "8274ebc2-6510-4c01-9f6b-39a71ebd1360",
    "plantId": "a4f69009-4301-49ea-9844-fe77e4645eaa",
    "status": "ACTIVE",
    "joinedAt": "2026-10-04T17:20:55.882380Z"
  },
  {
    "id": "f25c4b4e-ac86-4599-b9a0-59babe08deb5",
    "tournamentId": "9c25b3e8-d905-44b2-9b21-a4868730f854",
    "userId": "2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1",
    "plantId": "c5f7c9b4-880b-4116-a22f-3523859496dd",
    "status": "ACTIVE",
    "joinedAt": "2026-10-04T17:20:55.882380Z"
  },
  {
    "id": "fa162ece-282d-40e2-b630-6822f599ed59",
    "tournamentId": "9c25b3e8-d905-44b2-9b21-a4868730f854",
    "userId": "06030188-5dee-4fb3-b31b-1ada1c5345a1",
    "plantId": "b24eaefb-636a-40f7-bf5b-7e18dc92043b",
    "status": "ACTIVE",
    "joinedAt": "2026-10-04T17:20:55.882380Z"
  }
]
```

## 9. Раунд 1 — голосование, выбывает p3

### 9.1 Раунды турнира (окно раунда 1)

```http
GET /tournaments/9c25b3e8-d905-44b2-9b21-a4868730f854/rounds
X-Demo-User-Id: 06030188-5dee-4fb3-b31b-1ada1c5345a1
```

**Ответ: `200`**

```json
[
  {
    "id": "489c2efe-d5c4-4a73-a536-1aaded3a45a0",
    "sequence": 1,
    "status": "OPEN",
    "opensAt": "2026-10-04T17:20:55.882380Z",
    "closesAt": "2026-10-04T17:21:10.882380Z"
  }
]
```

### 9.2 p1 голосует DISLIKE против p3

```http
PUT /windows/489c2efe-d5c4-4a73-a536-1aaded3a45a0/entries/f25c4b4e-ac86-4599-b9a0-59babe08deb5/vote
X-Demo-User-Id: 06030188-5dee-4fb3-b31b-1ada1c5345a1
{"value":"DISLIKE"}
```

**Ответ: `200`**

```json
{
  "score": -1
}
```

### 9.3 p2 голосует DISLIKE против p3

```http
PUT /windows/489c2efe-d5c4-4a73-a536-1aaded3a45a0/entries/f25c4b4e-ac86-4599-b9a0-59babe08deb5/vote
X-Demo-User-Id: 8274ebc2-6510-4c01-9f6b-39a71ebd1360
{"value":"DISLIKE"}
```

**Ответ: `200`**

```json
{
  "score": -2
}
```

### 9.4 p3 голосует LIKE за p1

```http
PUT /windows/489c2efe-d5c4-4a73-a536-1aaded3a45a0/entries/fa162ece-282d-40e2-b630-6822f599ed59/vote
X-Demo-User-Id: 2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1
{"value":"LIKE"}
```

**Ответ: `200`**

```json
{
  "score": 1
}
```

### 9.5 Лидерборд раунда 1

```http
GET /tournaments/9c25b3e8-d905-44b2-9b21-a4868730f854/leaderboard?windowId=489c2efe-d5c4-4a73-a536-1aaded3a45a0
X-Demo-User-Id: 06030188-5dee-4fb3-b31b-1ada1c5345a1
```

**Ответ: `200`**

```json
{
  "windowId": "489c2efe-d5c4-4a73-a536-1aaded3a45a0",
  "sequence": 1,
  "status": "OPEN",
  "closesAt": "2026-10-04T17:21:10.882380Z",
  "items": [
    {
      "position": 1,
      "entryId": "fa162ece-282d-40e2-b630-6822f599ed59",
      "userId": "06030188-5dee-4fb3-b31b-1ada1c5345a1",
      "score": 1,
      "result": "ACTIVE"
    },
    {
      "position": 2,
      "entryId": "d2d61198-7212-494d-b05f-9a27b68423a0",
      "userId": "8274ebc2-6510-4c01-9f6b-39a71ebd1360",
      "score": 0,
      "result": "ACTIVE"
    },
    {
      "position": 3,
      "entryId": "f25c4b4e-ac86-4599-b9a0-59babe08deb5",
      "userId": "2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1",
      "score": -2,
      "result": "ACTIVE"
    }
  ]
}
```

## 10. Закрытие раунда 1 → раунд 2 — выбывает p2

### 10.1 Продвижение дедлайна закрытия окна (админ)

```http
POST /internal/demo/jobs/run-due
X-Demo-User-Id: 02d6f959-4ab2-457a-ace4-38f949459665
```

**Ответ: `200`**

```json
{
  "globalFinalsOpened": 0,
  "globalEpochsOpened": 0,
  "globalQualificationClosed": 0,
  "processed": 0,
  "globalFinalClosed": 0,
  "closedWindows": 0
}
```

### 10.2 Раунды: открыт раунд 2

```http
GET /tournaments/9c25b3e8-d905-44b2-9b21-a4868730f854/rounds
X-Demo-User-Id: 06030188-5dee-4fb3-b31b-1ada1c5345a1
```

**Ответ: `200`**

```json
[
  {
    "id": "489c2efe-d5c4-4a73-a536-1aaded3a45a0",
    "sequence": 1,
    "status": "CLOSED",
    "opensAt": "2026-10-04T17:20:55.882380Z",
    "closesAt": "2026-10-04T17:21:10.882380Z"
  },
  {
    "id": "5f980a28-99df-495c-a5e4-a85cd7230aab",
    "sequence": 2,
    "status": "OPEN",
    "opensAt": "2026-10-04T17:21:11.935398Z",
    "closesAt": "2026-10-04T17:21:26.935398Z"
  }
]
```

### 10.3 p1 голосует DISLIKE против p2

```http
PUT /windows/5f980a28-99df-495c-a5e4-a85cd7230aab/entries/d2d61198-7212-494d-b05f-9a27b68423a0/vote
X-Demo-User-Id: 06030188-5dee-4fb3-b31b-1ada1c5345a1
{"value":"DISLIKE"}
```

**Ответ: `200`**

```json
{
  "score": -1
}
```

### 10.4 p3 (выбывший в раунде 1, допущение 9 — голосует до FINISHED) DISLIKE против p2

```http
PUT /windows/5f980a28-99df-495c-a5e4-a85cd7230aab/entries/d2d61198-7212-494d-b05f-9a27b68423a0/vote
X-Demo-User-Id: 2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1
{"value":"DISLIKE"}
```

**Ответ: `200`**

```json
{
  "score": -2
}
```

### 10.5 Лидерборд раунда 2

```http
GET /tournaments/9c25b3e8-d905-44b2-9b21-a4868730f854/leaderboard?windowId=5f980a28-99df-495c-a5e4-a85cd7230aab
X-Demo-User-Id: 06030188-5dee-4fb3-b31b-1ada1c5345a1
```

**Ответ: `200`**

```json
{
  "windowId": "5f980a28-99df-495c-a5e4-a85cd7230aab",
  "sequence": 2,
  "status": "OPEN",
  "closesAt": "2026-10-04T17:21:26.935398Z",
  "items": [
    {
      "position": 1,
      "entryId": "fa162ece-282d-40e2-b630-6822f599ed59",
      "userId": "06030188-5dee-4fb3-b31b-1ada1c5345a1",
      "score": 0,
      "result": "ACTIVE"
    },
    {
      "position": 2,
      "entryId": "d2d61198-7212-494d-b05f-9a27b68423a0",
      "userId": "8274ebc2-6510-4c01-9f6b-39a71ebd1360",
      "score": -2,
      "result": "ACTIVE"
    }
  ]
}
```

## 11. Финал: последний выживший — победитель

### 11.1 Продвижение дедлайна закрытия окна (админ)

```http
POST /internal/demo/jobs/run-due
X-Demo-User-Id: 02d6f959-4ab2-457a-ace4-38f949459665
```

**Ответ: `200`**

```json
{
  "globalFinalsOpened": 0,
  "globalEpochsOpened": 0,
  "globalQualificationClosed": 0,
  "processed": 0,
  "globalFinalClosed": 0,
  "closedWindows": 0
}
```

### 11.2 Статус турнира

```http
GET /tournaments/9c25b3e8-d905-44b2-9b21-a4868730f854
X-Demo-User-Id: 676a2ad0-2367-4248-b88d-45f78d3fc83d
```

**Ответ: `200`**

```json
{
  "id": "9c25b3e8-d905-44b2-9b21-a4868730f854",
  "creatorId": "676a2ad0-2367-4248-b88d-45f78d3fc83d",
  "name": "Review Happy Path Cup",
  "description": "Ревью happy path: 3 участника, 2 раунда",
  "type": "PRIVATE",
  "status": "FINISHED",
  "algorithm": "ROUND_ELIMINATION",
  "registrationDeadline": "2026-10-04T17:20:54Z",
  "roundDurationSeconds": 15,
  "eliminationFraction": 0.5,
  "minParticipants": 2,
  "cancelReason": null,
  "tagIds": [
    "23a08e7b-f243-4255-bb05-d775132ca4d1"
  ],
  "createdAt": "2026-10-04T17:18:24.508922Z"
}
```

### 11.3 Результаты турнира

```http
GET /tournaments/9c25b3e8-d905-44b2-9b21-a4868730f854/results
X-Demo-User-Id: 676a2ad0-2367-4248-b88d-45f78d3fc83d
```

**Ответ: `200`**

```json
{
  "winnerEntryId": "fa162ece-282d-40e2-b630-6822f599ed59",
  "items": [
    {
      "entryId": "fa162ece-282d-40e2-b630-6822f599ed59",
      "userId": "06030188-5dee-4fb3-b31b-1ada1c5345a1",
      "plantId": "b24eaefb-636a-40f7-bf5b-7e18dc92043b",
      "status": "WINNER",
      "eliminatedInRound": null
    },
    {
      "entryId": "d2d61198-7212-494d-b05f-9a27b68423a0",
      "userId": "8274ebc2-6510-4c01-9f6b-39a71ebd1360",
      "plantId": "a4f69009-4301-49ea-9844-fe77e4645eaa",
      "status": "ELIMINATED",
      "eliminatedInRound": 2
    },
    {
      "entryId": "f25c4b4e-ac86-4599-b9a0-59babe08deb5",
      "userId": "2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1",
      "plantId": "c5f7c9b4-880b-4116-a22f-3523859496dd",
      "status": "ELIMINATED",
      "eliminatedInRound": 1
    }
  ]
}
```

## 12. Пост-состояние: судьба растений

### 12.1 Растение p1 (private) после турнира

```http
GET /plants/b24eaefb-636a-40f7-bf5b-7e18dc92043b
X-Demo-User-Id: 06030188-5dee-4fb3-b31b-1ada1c5345a1
```

**Ответ: `200`**

```json
{
  "id": "b24eaefb-636a-40f7-bf5b-7e18dc92043b",
  "ownerId": "06030188-5dee-4fb3-b31b-1ada1c5345a1",
  "assetId": "70b61384-8e39-4b93-a90d-d226ffcfdd19",
  "title": "Ромашка p1 (private)",
  "moderationStatus": "APPROVED",
  "lifeStatus": "ALIVE",
  "createdAt": "2026-10-04T17:18:24.772076Z",
  "diedAt": null,
  "archivedAt": null
}
```

### 12.2 Растение p2 (private) после турнира

```http
GET /plants/a4f69009-4301-49ea-9844-fe77e4645eaa
X-Demo-User-Id: 8274ebc2-6510-4c01-9f6b-39a71ebd1360
```

**Ответ: `200`**

```json
{
  "id": "a4f69009-4301-49ea-9844-fe77e4645eaa",
  "ownerId": "8274ebc2-6510-4c01-9f6b-39a71ebd1360",
  "assetId": "59b9a72e-0ea5-4498-8695-0608e169c380",
  "title": "Ромашка p2 (private)",
  "moderationStatus": "APPROVED",
  "lifeStatus": "DEAD",
  "createdAt": "2026-10-04T17:18:27.028018Z",
  "diedAt": "2026-10-04T17:21:27.991725Z",
  "archivedAt": null
}
```

### 12.3 Растение p3 (private) после турнира

```http
GET /plants/c5f7c9b4-880b-4116-a22f-3523859496dd
X-Demo-User-Id: 2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1
```

**Ответ: `200`**

```json
{
  "id": "c5f7c9b4-880b-4116-a22f-3523859496dd",
  "ownerId": "2c4c80f5-f65f-4d79-b956-8d1a61c9b3e1",
  "assetId": "d1b0049b-2803-4ce1-84a0-e738d0008f41",
  "title": "Ромашка p3 (private)",
  "moderationStatus": "APPROVED",
  "lifeStatus": "DEAD",
  "createdAt": "2026-10-04T17:18:29.284546Z",
  "diedAt": "2026-10-04T17:21:11.941449Z",
  "archivedAt": null
}
```

## Итог

| Статус | Шаг | HTTP |
|---|---|---|
| ✅ | Создан модератор (user) | 201 |
| ✅ | Роль moderator назначена | 200 |
| ✅ | Создан участник p1 | 201 |
| ✅ | Создан участник p2 | 201 |
| ✅ | Создан участник p3 | 201 |
| ✅ | Локация p1 сохранена | 200 |
| ✅ | Локация p2 сохранена | 200 |
| ✅ | Локация p3 сохранена | 200 |
| ✅ | Файл p1a загружен | 201 |
| ✅ | Растение p1 (global) создано (PENDING) | 201 |
| ✅ | Модерация p1 (global): APPROVED / PLANT_DETECTED | 200 |
| ✅ | Файл p2a загружен | 201 |
| ✅ | Растение p2 (global) создано (PENDING) | 201 |
| ✅ | Модерация p2 (global): APPROVED / PLANT_DETECTED | 200 |
| ✅ | Файл p3a загружен | 201 |
| ✅ | Растение p3 (global) создано (PENDING) | 201 |
| ✅ | Модерация p3 (global): APPROVED / PLANT_DETECTED | 200 |
| ✅ | Конфигурация глобального турнира получена | 200 |
| ✅ | p1 в очереди глобального турнира (QUEUED) | 201 |
| ✅ | Участие p1: QUEUED | 200 |
| ✅ | p2 в очереди глобального турнира (QUEUED) | 201 |
| ✅ | Участие p2: QUEUED | 200 |
| ✅ | p3 в очереди глобального турнира (QUEUED) | 201 |
| ✅ | Участие p3: QUEUED | 200 |
| ✅ | Тег создан | 201 |
| ✅ | Турнир создан (DRAFT) | 201 |
| ✅ | Регистрация открыта (REGISTRATION_OPEN) | 200 |
| ✅ | Приглашение p1 создано | 201 |
| ✅ | Приглашение p2 создано | 201 |
| ✅ | Приглашение p3 создано | 201 |
| ✅ | Файл p1b загружен | 201 |
| ✅ | Растение p1 (private) создано | 201 |
| ✅ | Приглашение p1 найдено | 200 |
| ✅ | p1 принял: READY | 200 |
| ✅ | Файл p2b загружен | 201 |
| ✅ | Растение p2 (private) создано | 201 |
| ✅ | Приглашение p2 найдено | 200 |
| ✅ | p2 принял: READY | 200 |
| ✅ | Файл p3b загружен | 201 |
| ✅ | Растение p3 (private) создано | 201 |
| ✅ | Приглашение p3 найдено | 200 |
| ✅ | p3 принял: READY | 200 |
| ✅ | Дедлайны обработаны: processed=0 | 200 |
| ✅ | Турнир: RUNNING | 200 |
| ✅ | Участия получены | 200 |
| ✅ | Окно раунда 1: 489c2efe-d5c4-4a73-a536-1aaded3a45a0 (closesAt 2026-10-04T17:21:10.882380Z) | 200 |
| ✅ | Голос p1 учтён (score -1) | 200 |
| ✅ | Голос p2 учтён (score -2) | 200 |
| ✅ | Голос p3 учтён (score 1) | 200 |
| ✅ | Лидерборд раунда 1 получен | 200 |
| ✅ | Окно раунда 1 закрыто | 200 |
| ✅ | Окно раунда 2: 5f980a28-99df-495c-a5e4-a85cd7230aab (closesAt 2026-10-04T17:21:26.935398Z) | 200 |
| ✅ | Голос p1 учтён (score -1) | 200 |
| ✅ | Голос выбывшего p3 учтён (score -2) | 200 |
| ✅ | Лидерборд раунда 2 получен | 200 |
| ✅ | Окно раунда 2 закрыто | 200 |
| ✅ | Турнир: FINISHED | 200 |
| ✅ | Результаты получены | 200 |
| ✅ | Победитель — p1 (entry fa162ece-282d-40e2-b630-6822f599ed59) | — |
| ✅ | Растение p1: ALIVE | 200 |
| ✅ | Растение p2: DEAD | 200 |
| ✅ | Растение p3: DEAD | 200 |

**Результат: happy path пройден полностью — все шаги успешны. ✅**
