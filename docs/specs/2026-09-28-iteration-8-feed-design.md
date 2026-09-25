# Дизайн итерации 8: лента и гостевые сессии

Дата: 2026-09-28. Статус: предложен. Источник: `docs/requirements.md`
(разделы 9, 2, 4.3, 11, 13), `docs/plans/2026-09-23-roadmap.md` (итерация 8),
`docs/domain/adr/ADR-002-feed-own-projection.md`.

## Решения

1. **`GuestSession` — агрегат tournaments** (раздел 11: таблица `guest_session`
   в схеме tournaments; `POST /guest-sessions` — контекст tournaments). Поля:
   id, tokenHash (SHA-256 hex, UNIQUE), createdAt, expiresAt. Токен — 32 байта
   `SecureRandom` → Base64URL, возвращается клиенту один раз в теле 201 и
   больше нигде не существует: в БД, логах и ответах — только хэш. TTL —
   `plantarena.guests.session-ttl` (по умолчанию 24h). Активность:
   `now < expiresAt` (полуинтервал, как окна). Агрегат неизменяем после
   создания — без version; повторная выдача = новая сессия.
2. **Субъект GUEST** (раздел 9): `VotingSubject.guest(sessionId)` →
   subjectKey `GUEST:<sessionId>`; `isUser(...)` — false (гость не владеет
   entry, самоголосование неприменимо — допущение 6). Для авторизованного
   пользователя субъект всегда USER, даже при переданном `X-Guest-Token`
   (раздел 9). `subjectKey` в `vote` уже VARCHAR — схема голосов не меняется.
3. **Разрешение гостя — в use case, не в CurrentActor** (гостевые сессии —
   понятие tournaments, identity не может их проверять: направление
   зависимостей tournaments → identity). Контроллеры voting/feed читают
   заголовок `X-Guest-Token` и передают сырое значение в use case рядом с
   `CurrentActor`. `VotingService`: идентифицированный → USER (токен
   игнорируется); гость без токена → 401; гость с неизвестным/истёкшим
   токеном → 401; иначе GUEST(sessionId). PRIVATE-окно для гостя — 404
   `TOURNAMENT_NOT_FOUND` (турнир скрыт политикой приватности, раздел 13),
   глобальные окна (QUALIFICATION/FINAL) — разрешены (раздел 2). my-vote —
   те же правила.
4. **Минимальная защита от накрутки** (раздел 9): in-memory фиксированное
   окно 1 минута — создание сессий на IP
   (`plantarena.guests.session-creation-limit-per-minute`, по умолчанию 10) и
   операции голосования гостя на сессию
   (`plantarena.guests.vote-limit-per-minute`, по умолчанию 30). Превышение —
   `RateLimitExceededException(retryAfterSeconds)` → 429 `RATE_LIMITED` +
   заголовок `Retry-After`. Журнал подозрительных действий — выходной порт
   `AbuseSignals` с логирующим адаптером (уведомления/anti-doping — лаба №4).
   Состояние лимитов сбрасывается рестартом — осознанная минимальность;
   IP — дополнительный сигнал, не идентификатор человека.
5. **Feed — собственная проекция (ADR-002)**: схема `feed`, таблица
   `feed_card` (id, window_id, tournament_id, scope, cluster_id, entry_id,
   user_id, plant_id, asset_id, title, owner_display_name, joined_at,
   closes_at, created_at; UNIQUE(window_id, entry_id)). Обновляется
   опубликованными событиями: новые `VotingWindowOpenedEvent` /
   `VotingWindowClosedEvent` (tournaments.api.event, конверт как у
   существующих). Opened несёт состав (entryId, userId, plantId, joinedAt) и
   параметры окна; feed обогащает карточки через `plants.api.PlantDirectory`
   (title, assetId; неживое растение пропускается) и
   `identity.api.UserDirectory` (displayName) в ACL-адаптерах и пишет
   карточки в той же tx (синхронный `@EventListener`, как
   `PlantModerationDecidedHandler`). Closed удаляет все карточки окна —
   выбывшие и выжившие; выжившие возвращаются событием Opened следующего
   окна. Карточка — снимок на момент открытия окна (смена title/displayName
   не ретранслируется — документировано). Публикуют: StartTournamentService
   (первое окно), CloseVotingWindowService (закрытие + следующий раунд),
   AdvanceGlobalCompetitionService (квалификационные и финальные окна).
6. **Псевдослучайный порядок** (раздел 9): seed (long) выдаётся сервером при
   первом запросе ленты (`SecureRandom`) и переносится курсором. Sort key
   вычисляется в SQL: `hashtextextended(id::text, :seed)` — детерминированная
   функция PostgreSQL; таблица не грузится в память; индекса по выражению с
   параметром нет — стоимость сортировки O(k log k) по отфильтрованным
   строкам на страницу (документируется). Порядок: sortKey DESC, id DESC
   (tie-break по стабильному ID). Контракт репозитория фиксирует свойства
   (детерминизм, keyset без пропусков/дублей), а не значения хэша: JPA-адаптер
   использует hashtextextended, in-memory фейк — собственный Java-PRF с теми
   же свойствами.
7. **`FeedCursor` (HMAC)**: value object в feed.domain — seed,
   snapshotCutoff, lastSortKey (null на первой странице), lastId, subjectKey.
   Кодирование: `base64url(canonical) + '.' + base64url(HMAC-SHA256)`;
   canonical — `seed|cutoffEpochMilli|lastSortKey|lastId|subjectKey`; ключ —
   `plantarena.feed.cursor-secret` (ENV `FEED_CURSOR_SECRET`). Проверка —
   пересчёт + `MessageDigest.isEqual`. Невалидный/подменённый (в т.ч. чужой
   subjectKey) → 400 `FEED_CURSOR_INVALID`; истёкший (snapshotCutoff старше
   `plantarena.feed.cursor-ttl`, по умолчанию 1h) → 410
   `FEED_CURSOR_EXPIRED` («начните новую ленту» — запрос без cursor).
   snapshotCutoff: карточки с created_at > cutoff исключаются — новые
   участники появляются после обновления ленты; закрытие окна может убирать
   карточки между запросами, но пройденный ключ не повторяется (keyset).
8. **Права и фильтры повторно на каждой странице** (раздел 9): окно ещё
   открыто (closes_at > now — защита от лага проекции), scope: гость — только
   QUALIFICATION/FINAL; USER — глобальные + PRIVATE турниров, где он допущен
   к старту (включая выбывших — допущение 9; организатор-не-участник карточек
   не видит); чужие entry (owner ≠ субъект); ещё не оценённые субъектом в
   этом окне. Данные — read-контракт `tournaments.api.FeedDirectory`:
   `findVotedEntryIdsInOpenWindows(subjectKey)`,
   `findParticipatedTournamentIds(userId)`; ACL-адаптер feed. Никаких JOIN в
   чужие таблицы; пустые множества фильтров заменяются невозможным
   sentinel-UUID (семантика «ничего не совпало»).
9. **`GetFeedUseCase`**: вход CurrentActor + guestToken + limit + cursor.
   Субъект: USER → `USER:<userId>`; гость без токена → 401; гость с токеном →
   `tournaments.api.GuestSessionDirectory.activeSessionId(token)` →
   `GUEST:<sessionId>`. limit 1–50 по умолчанию 20 (вне диапазона → 400).
   Запрашивается limit+1 → hasNext; ответ `{items, nextCursor, hasNext}` без
   total и без `X-Total-Count` (раздел 13). Карточка: windowId, scope,
   tournamentId, entryId, plantId, title, imageUrl (`/api/v1/files/{assetId}`),
   owner {userId, displayName}, closesAt. Публичный профиль — отдельный
   ограниченный DTO, не `GET /users/{id}`.
10. **REST (раздел 13)**: `POST /api/v1/guest-sessions` — публично, 201
    `{token, expiresAt}` (токен один раз), 429 при лимите; `GET
    /api/v1/feed?limit&cursor` — U либо guest token. Заголовок
    `X-Guest-Token` документируется в Swagger через `OperationCustomizer`
    (как X-Demo-User-Id, ADR-005). operationId — префиксы `guest-`/`feed-`.
11. **Порядок задач** (урок итераций 5–7): порты и красные приёмочные IT →
    домен → persistence → application → in-адаптеры; @Service-бины появляются
    только после JPA-реализаций портов. Каждый коммит — зелёный
    `./mvnw verify` (кроме Task 1 с красными приёмочными — установленный
    паттерн внешнего цикла TDD).
12. **Документация**: глоссарий (GuestSession, FeedCursor, Карточка ленты,
    Seed ленты), aggregates.md (инварианты GuestSession, feed-проекция),
    ADR-013 (гостевые сессии и минимальная защита от накрутки), ADR-002 →
    «реализовано (итерация 8)», context-map (взаимодействия feed:
    события VotingWindowOpened/Closed, read FeedDirectory /
    GuestSessionDirectory / PlantDirectory / UserDirectory), README (раздел
    «Лента и гостевые сессии»), `.env.example`/compose (`FEED_CURSOR_SECRET`).
