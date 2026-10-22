# Демо через Bruno: админ → модератор → 3 юзера → турнир → победитель

Пошаговая демонстрация закрытого турнира по коллекции `PlantArena API`
(Bruno). Сценарий: bootstrap-админ создаёт модератора, модератор — трёх
участников, каждый подаёт растение, модератор проводит закрытый турнир
между ними; в раунде 1 выбывает юзер 3, в раунде 2 — юзер 2, победитель —
юзер 1. Повторяет `scripts/happy-path-review.sh`, но вручную из Bruno.

## 0. Запуск и подготовка

1. `docker compose up --build`; health: http://localhost:8080/actuator/health.
2. Открыть в Bruno коллекцию `PlantArena API`.
3. `adminUserId` уже заполнен; при чистой БД взять ID из БД:

```bash
docker compose exec postgres psql -U postgres -d plantarena -t -A -c \
  "select id from identity.app_user where email_normalized = 'admin@plantarena.local'"
```

### Механика «слотов»

Переменные, которые меняются от запроса к запросу, заданы в коллекции
**несколькими копиями с комментариями** (напр. `userUserId` ×3,
`imageFilePath` ×6): активна одна копия, остальные `disabled`. Перед
каждым запросом: включить копию с нужным комментарием и вписать значение
из ответа предыдущего запроса. Копии с пустыми значениями — заполняются
по ходу демо.

Изображения: `imageFilePath` указывает на локальные файлы
(`/Users/vovabag/Downloads/pics/`); для закрытого турнира используются
слоты «private tournament» (1.jpeg, 3.jpg, 5.jpg — по одному на юзера).

## 1. Модератор

| # | Запрос (папка / имя) | Слоты | Куда сохранить ответ |
|---|---|---|---|
| 1.1 | Identity / Create user - Admin | `newUserEmail` → «moderator email», `newUserDisplayName` → «moderator display name» | `id` → `moderatorUserId`, `organizerUserId`, `targetUserId` (слот «moderator id») |
| 1.2 | Identity / Grant moderator role - Admin | `targetUserId` = слот «moderator id» | 200, идемпотентно |

## 2. Три юзера (создаёт модератор)

Повторить ×3 (юзер 1, 2, 3) — **Identity / Create user - Moderator**:

- `newUserEmail` → слот «user N email», `newUserDisplayName` → слот
  «user N display name»;
- `id` из ответа → в слоты «user N id» переменных `userUserId`,
  `ownerUserId`, `participantUserId`, `invitedUserId` и `targetUserId`
  (слот «user N id (invite to tournament)»).

## 3. Растения (по одному на юзера)

Повторить ×3 для юзера N:

| # | Запрос | Слоты | Ответ |
|---|---|---|---|
| 3.1 | Files / Upload image - Owner | `ownerUserId` → «user N», `imageFilePath` → «user N image — private tournament» | 201, `id` → `assetId` (слот «user N») |
| 3.2 | Plants / Submit plant - Owner | `ownerUserId` «user N», `assetId` «user N», `plantTitle` «user N» | 201, `id` → `plantId` (слот «user N») |
| 3.3 | Plants / Get plant moderation status - Owner | `ownerUserId` «user N», `plantId` «user N» | подождать ~2 с (scheduler) → `APPROVED` / `PLANT_DETECTED` |

Модерация автоматическая (ONNX MobileNetV2). Если статус ещё `PENDING` —
повторить 3.3 или разово выполнить Demo jobs / Run due jobs - Admin.

## 4. Турнир (создаёт модератор)

| # | Запрос | Параметры | Ответ |
|---|---|---|---|
| 4.1 | Tags / Create tag - Moderator | `tagName` | 201, `id` → `tagId`; вписать в `tagIdsJson`: `["<tagId>"]` |
| 4.2 | Tournaments / Create tournament - Moderator | `tournamentName`, `registrationDeadline` = **сейчас +2 мин** (UTC, `2026-10-22T12:34:56Z`), `roundDurationSeconds` = `30`, `eliminationFraction` = `0.5`, `minParticipants` = `2`, `tagIdsJson` | 201 DRAFT, `id` → `tournamentId` |
| 4.3 | Tournaments / Open registration - Organizer | `organizerUserId` = модератор, `tournamentId` | 200 REGISTRATION_OPEN |
| 4.4 | Tournaments / Invite user - Organizer ×3 | `targetUserId` → слот «user N id (invite to tournament)» | 201 ×3 |

## 5. Принятие приглашений

Повторить ×3 для юзера N:

| # | Запрос | Слоты |
|---|---|---|
| 5.1 | Invitations / List my invitations - Invitee | `invitedUserId` «user N» → взять `id` приглашения турнира → `invitationId` (слот «user N») |
| 5.2 | Invitations / Accept invitation - Invitee | `invitedUserId` «user N», `invitationId` «user N», `plantId` «user N» → 200 `READY` |

## 6. Старт

1. Дождаться дедлайна регистрации (или подождать scheduler 2 с после
   дедлайна) → **Demo jobs / Run due jobs - Admin** → `processed ≥ 1`.
2. **Tournaments / Get tournament - Organizer** → `RUNNING`.
3. **Tournaments / List tournament entries - Organizer** → три `ACTIVE`
   записи; `id` каждой → `entryId` (слот соответствующего юзера).

## 7. Раунд 1 — выбывает юзер 3

1. **Tournaments / List tournament rounds - Participant** (любой юзер) →
   `id` открытого окна → `windowId` (слот «round 1»), запомнить `closesAt`.
2. Голосование — **Voting / Cast vote - Participant** (тело `{"value":
   "{{voteValue}}"}`):

| Голосующий (`participantUserId`) | Слот `entryId` | `voteValue` |
|---|---|---|
| юзер 1 | юзер 3 | DISLIKE |
| юзер 2 | юзер 3 | DISLIKE |
| юзер 3 | юзер 1 | LIKE |

3. **Tournaments / Get tournament leaderboard - Participant** (`windowId`
   «round 1») → у юзера 3 счёт −2.

## 8. Раунд 2 — выбывает юзер 2

1. После `closesAt` раунда 1 → **Demo jobs / Run due jobs - Admin**.
2. **List tournament rounds - Participant** → `id` нового открытого окна →
   `windowId` (слот «round 2»).
3. Голоса: юзер 1 → DISLIKE против entry юзера 2; юзер 3 (выбывший —
   голосует до FINISHED, допущение 9) → DISLIKE против entry юзера 2.

## 9. Финал — просмотр победителя

1. После `closesAt` раунда 2 → **Demo jobs / Run due jobs - Admin**.
2. **Tournaments / Get tournament - Organizer** → `FINISHED`.
3. **Tournaments / List tournament results - Organizer** →
   `winnerEntryId` = entry юзера 1, у юзеров 2/3 — поражения.
4. (Опционально) **Plants / Get plant - Owner** ×3: растение юзера 1 —
   `ALIVE`, юзеров 2/3 — `DEAD` (их изображения навсегда запрещены —
   повторная подача той же картинки → 409 `IMAGE_RESTRICTED`).

## Повторный прогон

- Email уникален: при повторе поменять слоты `newUserEmail` (напр.
  `demo-user1-2@example.test`) — иначе 409.
- Проигравшие изображения запрещены навсегда: для нового прогона взять
  другие файлы (запасной `7.jpg` или новые кропы).
- Турнир: `registrationDeadline` снова «сейчас +2 мин».
