# Демо-сценарии через Swagger UI

Порядок демонстрации всех сценариев лабы №1 (раздел 13 требований).
Swagger UI: http://localhost:8080/swagger-ui/index.html.

Идентификация (ADR-005): заголовок `X-Demo-User-Id: <UUID>` (профиль `dev`).
Отсутствие заголовка — гость; роли всегда из БД. Гостевое голосование —
заголовок `X-Guest-Token: <token>` (см. сценарий 8). Дедлайны не ждём:
`POST /api/v1/internal/demo/jobs/run-due` (M/A) продвигает наступившие
дедлайны через те же use cases, что и scheduler.

## 0. Запуск и вход

1. `docker compose up --build` — app + PostgreSQL, миграции применяются
   автоматически, healthcheck: http://localhost:8080/actuator/health.
2. Bootstrap-админ создаётся из ENV (`BOOTSTRAP_ADMIN_EMAIL`/`_PASSWORD`,
   см. `.env.example`); повторный запуск дубль не создаёт.
3. ID админа для `X-Demo-User-Id` (отдельной ручки нет — сознательно,
   лаба №3 добавит JWT-логин):

```bash
docker compose exec postgres psql -U postgres -d plantarena -t -A -c \
  "select id from identity.app_user where email_normalized = 'admin@plantarena.local'"
```

## 1. identity — пользователи и роли

1. `POST /api/v1/users` (X-Demo-User-Id: админ): `{"email":"alice@example.com",
   "password":"password-9","displayName":"Alice"}` → 201, `id` — запомнить.
   Модератор создаёт только USER; ролей в DTO нет.
2. `GET /api/v1/me` (X-Demo-User-Id: alice) → 200 профиль без passwordHash.
3. `PUT /api/v1/me/location` (alice): `{"latitude":55.7558,"longitude":37.6173}`
   → 200. Координаты нужны для глобального турнира (сценарий 7).
4. `PUT /api/v1/users/{bobId}/roles/moderator` (админ) → 200, идемпотентно;
   `DELETE .../roles/moderator` → 200, идемпотентно (снять, не удаляя USER).
5. Негативные: `GET /api/v1/users` без заголовка → 401; `PATCH /users/{id}` с
   `roles` в теле → 200, поле игнорируется (произвольные роли запрещены).

## 2. media — загрузка изображений

1. `POST /api/v1/files` (alice, multipart `file`, JPEG/PNG ≤ 10 MiB) → 201:
   `id` (assetId), метаданные (MIME по фактическому содержимому), без путей хранилища.
2. Негативные: текстовый файл с расширением .png → 415; > 10 MiB → 413;
   без заголовка → 401.
3. `GET /api/v1/files/{assetId}` — владелец всегда; чужим файл доступен только
   через одобренное растение (сценарий 4), иначе 404.

## 3. plants — подача заявки

1. `POST /api/v1/plants` (alice): `{"assetId":"...","title":"Мой фикус"}` →
   201, `PENDING`/`ALIVE`; файл задействован (повторное растение на тот же
   файл → 409 `ASSET_IN_USE`).
2. `GET /api/v1/plants/{id}/moderation` (alice) → статус, причина,
   `retryUploadAllowed`.
3. `PATCH /api/v1/plants/{id}` (alice) — только `title`; `DELETE` — архивация
   вне активного резерва (в сценарии 5–7 даст 409 `PLANT_UNDER_RESERVATION`).

## 4. Модерация — автоматическое распознавание

1. После `POST /plants` публикуется `PlantSubmitted`, задание обрабатывается
   автоматически (scheduler 2с). Для демо: `POST /api/v1/internal/demo/jobs/
   run-due` (админ) → `processed`.
2. `GET /api/v1/plants/{id}/moderation` (alice): фото растения → `APPROVED`
   (`PLANT_DETECTED`); фото без растения → `REJECTED` (`NOT_A_PLANT`).
3. Отклонение — не гибель и не запрет: повторная загрузка (новый файл →
   новый `POST /plants`) — новая заявка с новым ID.
4. Настоящий ONNX-инференс (MobileNetV2): `./mvnw verify -P inference` —
   эталонные ромашка/собака (ADR-009).

## 5. Закрытый турнир — от черновика до старта

1. `POST /api/v1/tags` (модератор/админ): `{"name":"succulents"}` → 201.
2. `POST /api/v1/tournaments` (модератор): name, `registrationDeadline`
   (например `+2 мин`), `roundDurationSeconds: 30`, `eliminationFraction:
   0.5`, `minParticipants: 2`, `tagIds` → 201 DRAFT.
3. `POST /api/v1/tournaments/{id}/open-registration` → 200.
4. `POST /api/v1/tournaments/{id}/invitations` (organizer): `{"userId":
   "<aliceId>"}` → 201; дубль → 409. Пригласить 2+ пользователей.
5. Каждый приглашённый: `GET /api/v1/me/invitations` → свой invite;
   `POST /api/v1/invitations/{id}/accept` c `{"plantId":"<APPROVED-растение>"}` →
   200 `READY` (резерв изображения: повторное использование той же картинки в
   другом турнире → 409 `IMAGE_ALREADY_RESERVED` — конфликт резерва).
6. `POST /api/v1/tournaments/{id}/start` до дедлайна → 409; после —
   `run-due` (или scheduler) стартует: `GET /api/v1/tournaments/{id}` →
   `RUNNING`, `GET .../entries` — участия ACTIVE.
7. Отмена при нехватке: турнир с 1 READY после дедлайна → CANCELLED
   `INSUFFICIENT_PARTICIPANTS`, резервы освобождены, растения живы.

## 6. Голосование и раунды

1. `GET /api/v1/feed` (участник) → карточки окна; или `GET /api/v1/tournaments/
   {id}/rounds`, `.../leaderboard?windowId=...`.
2. `PUT /api/v1/windows/{windowId}/entries/{entryId}/vote` (участник bob за
   растение alice): `{"value":"LIKE"}` → 200 `score: 1`; повтор LIKE → 1;
   `DISLIKE` → −1 (дельта −2); `DELETE .../vote` → 204, счёт 0;
   `GET .../my-vote` → текущий голос.
3. Самоголосование alice за себя → 403 (допущение 6). Голос постороннего →
   404 (турнир скрыт). Голос после `closesAt` → 409 `VOTING_CLOSED`.
4. Закрытие раунда: `run-due` после closesAt → выбывший: растение DEAD
   (`GET /api/v1/plants/{id}`), изображение запрещено навсегда (повторная
   подача той же картинки → 409 `IMAGE_RESTRICTED` без retryAt), запись в
   `.../results`. Выживший → следующий раунд со счётом 0; единственный —
   WINNER, турнир FINISHED, резерв победителя освобождён.
5. Выбывший участник продолжает голосовать до FINISHED (допущение 9).

## 7. Глобальный турнир — гео-отбор и финал

1. Участники: `PUT /me/location` (разные ячейки geohash для разных кластеров,
   например Москва/СПб), APPROVED-растение → `POST /api/v1/global/entries` →
   201 QUEUED (резерв; вторая заявка того же пользователя → 409).
2. `GET /api/v1/global` — конфигурация и текущие окна (публично);
   `GET /api/v1/global/clusters` — кластеры текущей эпохи.
3. `run-due` после `epoch-duration`: открыта эпоха, квалификационные окна по
   кластерам. `GET /api/v1/global/clusters/{id}/leaderboard` — отбор кластера;
   `GET /api/v1/global/leaderboard?scope=FINAL` — финал (scope, windowId,
   closesAt, asOf).
4. Закрытие квалификации: top-1 кластера → финал (PROMOTED), остальные —
   гибель + COOLDOWN 24 ч на совпавшую картинку (409 с `retryAt`), новое
   участие другим изображением сразу разрешено (допущение 5).
5. Финал: из n ≥ 2 выбывает max(1, floor(n/2)) худших; единственный лидер
   остаётся; новых финалистов включает следующее окно. GLOBAL никогда не
   FINISHED (ADR-012).
6. `GET /api/v1/me/global-entry` — своё участие; `DELETE /api/v1/global/
   entries/{id}` — снятие из очереди (только QUEUED, иначе 409).

## 8. Лента и гостевые сессии

1. `POST /api/v1/guest-sessions` (без заголовков, публично) → 201
   `{"token","expiresAt"}` — токен показывается один раз; > 10/мин с одного
   IP → 429 + `Retry-After`.
2. `GET /api/v1/feed` (X-Guest-Token) → только глобальные карточки
   `{items,nextCursor,hasNext}` без total; `limit=2` → `hasNext: true`,
   пройти курсором до конца — страницы не пересекаются.
3. `PUT /api/v1/windows/{globalWindowId}/entries/{entryId}/vote`
   (X-Guest-Token) → 200; закрытое окно гостю → 404; без токена → 401;
   > 30/мин → 429.
4. `GET /api/v1/feed` (X-Demo-User-Id) — смешение: глобальные + закрытые
   турниры, где пользователь допущен к старту; оценённые субъектом карточки
   не предлагаются.
5. Негативные курсоры: `cursor=garbage` → 400 `FEED_CURSOR_INVALID`; курсор
   другого субъекта → 400; истёкший (TTL 1 ч) → 410 `FEED_CURSOR_EXPIRED`.
