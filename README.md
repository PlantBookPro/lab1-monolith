# Plant Arena — Лабораторная №2 (микросервисы)

Турниры растений: профили, загрузка изображений с автоматической модерацией
(ONNX), закрытые турниры с приглашениями, постоянный глобальный турнир с
географическим отбором, голосование, лента.

Монолит лабы №1 разрезан на 7 сервисов по bounded contexts + инфраструктура
(Eureka, Config Server, Gateway). Публичный API не изменился: те же
`/api/v1/**` через gateway на `:8080` — Bruno-коллекция `PlantArena API`
работает без изменений.

- Требования: `docs/requirements.md`; задание лабы №1: `docs/assignment-lab1.md`
- Архитектура лабы №2: `docs/architecture.md` (диаграммы, порты, события)
- Context map и эволюция лаба №1→№2→№4: `docs/domain/context-map.md`
- Решения: `docs/domain/adr/` (ADR-014..019 — лаба №2)
- Контракты внутренних API: `docs/api-contracts/`
- Исходный монолит лабы №1 удалён после завершения декомпозиции
  (доступен в ветке `main`)

## Запуск (одна команда)

```bash
docker compose up --build
```

Поднимает postgres (7 БД на сервис, ADR-019), eureka, config-server,
gateway и 7 сервисов. Gateway: http://localhost:8080.

### URL-карта

| Что | URL |
|---|---|
| API (gateway) | http://localhost:8080/api/v1/** |
| Eureka | http://localhost:8761 |
| Config Server | http://localhost:8888 |
| Swagger UI (по сервису) | http://localhost:8081..8087/swagger-ui/index.html |
| Actuator health | http://localhost:8080/actuator/health (gateway), аналогично на портах сервисов |

Порты сервисов: identity 8081, media 8082, plants 8083, moderation 8084,
geo 8085, tournaments 8086, feed 8087 (полная таблица — `docs/architecture.md`).

ENV (см. `.env.example`): `DB_USERNAME`/`DB_PASSWORD`, `BOOTSTRAP_ADMIN_*`
(bootstrap-админ identity), `FEED_CURSOR_SECRET` (HMAC-курсор ленты).

## Локальная разработка

Инфраструктура — в Docker, сервисы — из IDE/`bootRun`:

```bash
docker compose up -d eureka-server   # discovery для Feign; порт 8761 опубликован
psql -U postgres -f scripts/local/create-databases.sql   # 7 БД в локальном postgres :5432
./gradlew :tournaments-service:bootRun   # любой :<svc>:bootRun
```

Config-server для локального запуска не обязателен (`spring.config.import:
optional:configserver:...`): без него сервис берёт умолчания из локального
`application.yml` (`localhost:5432`, `localhost:8761`, `localhost:8888`;
переопределяется ENV `DB_URL`, `EUREKA_URL`, `CONFIG_SERVER_URL`). Если
config-server запущен, он отдаёт docker-хосты (`postgres:5432`) — переменные
ENV приоритетнее конфига с сервера. В Docker compose postgres доступен только
внутри сети (порт не публикуется); его БД создаёт
`scripts/docker/init-databases.sql` при первом старте volume.

## Проверка

```bash
./gradlew build              # 13 модулей лабы №2: unit + integrationTest (Testcontainers)
./gradlew :tournaments-service:test
./gradlew build -Pinference  # + настоящий ONNX-инференс (moderation)
```

CI (`.github/workflows/ci.yml`): `./gradlew build` на ubuntu-latest
(Testcontainers работает штатно).

## Демо-сценарии

Пошаговые сценарии через Swagger UI: [`docs/demo/scenarios.md`](docs/demo/scenarios.md);
демо закрытого турнира через Bruno: [`docs/demo/bruno-demo.md`](docs/demo/bruno-demo.md)
(коллекция работает против `:8080` без изменений — gateway сохраняет пути).

## Демо-идентификация (ADR-005/016)

В профилях `dev`/`test` пользователь передаётся заголовком `X-Demo-User-Id: <UUID>`.
Отсутствие заголовка = гость; неизвестный/деактивированный ID — 401; роли
всегда из БД identity (каждый сервис разрешает актёра через Feign — ADR-016).
Гостевые сессии: `POST /api/v1/guest-sessions` (публично) выдаёт токен один
раз; передаётся заголовком `X-Guest-Token`. Bootstrap-админ:
`BOOTSTRAP_ADMIN_EMAIL` + `BOOTSTRAP_ADMIN_PASSWORD` (ENV), повторный запуск
дубликат не создаёт.

## Файлы (media)

Загрузка изображения — `POST /api/v1/files` (multipart, часть `file`): JPEG/PNG,
до 10 MiB и 20 млн пикселей, формат проверяется по фактическому содержимому.
Ответ: `assetId` и метаданные (без внутренних путей хранилища). Повторная загрузка
тех же байтов создаёт новый asset. Отпечаток изображения (алгоритм v1) игнорирует
метаданные и альфа-канал — ADR-007.

- Скачивание — `GET /api/v1/files/{id}`: владелец — всегда; чужим файл доступен
  только через одобренное растение (ADR-008).
- Удаление — `DELETE /api/v1/files/{id}`: владелец или админ; задействованный
  растением файл — 409 `ASSET_IN_USE`.

Локальное хранилище: каталог `MEDIA_STORAGE_ROOT` (по умолчанию `./storage/media`,
в Docker Compose — volume `storage`).

## Растения (plants)

Подача заявки — `POST /api/v1/plants` (`assetId` своего файла + `title`): создаёт
растение PENDING/ALIVE, задействует файл (один файл — одно неархивированное
растение, ADR-008) и публикует `PlantSubmitted` (модерация получает его
polling'ом — ADR-015). Повторное использование запрещённого изображения —
409 `IMAGE_RESTRICTED`: навсегда после поражения в закрытом турнире, на 24 часа
после глобального (`retryAt` — срок истечения суточного).

- Список — `GET /api/v1/plants[?ownerId=]`: свои — все статусы, чужие — только
  APPROVED; пагинация `page`/`size`, заголовок `X-Total-Count`.
- Просмотр — `GET /api/v1/plants/{id}`: владелец/админ всегда; чужие — только
  одобренные (скрытое — 404).
- Переименование — `PATCH /api/v1/plants/{id}`: только владелец, только `title`.
- Архивация — `DELETE /api/v1/plants/{id}`: только владелец, вне активного
  резерва турнира (409 `PLANT_UNDER_RESERVATION`); скрывает растение и
  освобождает файл.
- Статус модерации — `GET /api/v1/plants/{id}/moderation`: только владелец.

Одобрение модерации делает файл растения публично видимым чужим
(`GET /api/v1/files/{id}`); архивация возвращает приватность (ADR-008).

## Модерация (moderation)

Поданная заявка проходит автоматическое распознавание (ADR-009): MobileNetV2
(ONNX Runtime) в moderation-service. Зелёное на фото — не гарантия: решает
классификатор; ошибка распознавателя — не решение, заявка остаётся PENDING и
будет повторена (backoff 1с → 2с → … → кап 1ч, без лимита попыток).
PENDING-растения сервис забирает polling'ом (5с), задания обрабатывает
poller'ом (2с) — ADR-015.

- Модель (~13 МБ) скачивается автоматически при сборке; offline-сборка не
  ломается (задания честно уходят в RETRY).
- Настоящий инференс на эталонных фото: `./gradlew build -Pinference`.
- Решение видно владельцу: `GET /api/v1/plants/{id}/moderation` — статус,
  причина (`PLANT_DETECTED`/`NOT_A_PLANT`) и `retryUploadAllowed`.

## Турниры (tournaments)

Закрытые (PRIVATE) турниры с приглашениями: модератор/админ создаёт DRAFT
(параметры + теги), приглашает пользователей; приём заявок — до дедлайна
регистрации. Принятие приглашения резервирует изображение растения через
plants (Feign, saga reserve→confirm/release — ADR-010/015): одобренное
модерацией растение → заявка READY сразу, идущая модерация →
ACCEPTED_PENDING_MODERATION (решение переводит в READY или возвращает в
INVITED — событие `PlantModerationDecided` по HTTP, ADR-015). Дедлайн
стартует турнир scheduler'ом (fixedDelay 2с) или вручную: READY ≥
minParticipants → RUNNING (резервы подтверждены); иначе CANCELLED с
освобождением резервов (растения не погибают).

- Создание и теги — `POST /api/v1/tournaments`, `POST /api/v1/tags` (M/A);
  список доступных — `GET /api/v1/tournaments?status=&tagId=` (X-Total-Count).
- Приглашения — `POST /api/v1/tournaments/{id}/invitations`,
  `GET /api/v1/me/invitations`, `POST /api/v1/invitations/{id}/accept|decline`.
- Старт/отмена — `POST /api/v1/tournaments/{id}/start|cancel`; участники —
  `GET /api/v1/tournaments/{id}/entries`.
- Диагностика (dev/test, M/A): `POST /api/v1/internal/demo/jobs/run-due` —
  тот же use case, что scheduler.
- Старт создаёт первый раунд `VotingWindow`; закрытие окна — выбывание худших,
  гибель выбывших (команда plants `registerDeath`), PERMANENT-запрет; один
  выживший — WINNER. Раунды и итоги — `GET /api/v1/tournaments/{id}/rounds|leaderboard|results`.

## Глобальный турнир (global)

Постоянный глобальный турнир: заявка `POST /api/v1/global/entries` (своё
APPROVED-растение, координаты в профиле обязательны — `PUT /me/location`)
→ очередь → эпоха отбора раз в 24 ч: гео-кластеры (geohash, geo-service —
Feign, ADR-012), квалификационное окно на непустой кластер. Закрытие: top-1 —
в финал (PROMOTED), остальные — гибель + суточный запрет совпавшей картинки +
освобождение резерва. Финал — непрерывные окна: из n ≥ 2 выбывает
max(1, floor(n/2)) худших. Порядок одновременных границ фиксирован;
scheduler идемпотентен (ADR-012).

- Конфигурация и текущие окна — `GET /api/v1/global` (публично).
- Кластеры эпохи — `GET /api/v1/global/clusters`; отбор кластера —
  `GET /api/v1/global/clusters/{id}/leaderboard`; финал —
  `GET /api/v1/global/leaderboard?scope=FINAL`.
- Своё участие — `GET /api/v1/me/global-entry`; снятие из очереди —
  `DELETE /api/v1/global/entries/{id}` (только QUEUED, иначе 409).
- Голосовать в глобальных окнах может любой идентифицированный пользователь
  или гость с активной сессией; самоголосование запрещено.

## Голосование (voting)

`PUT /api/v1/windows/{windowId}/entries/{entryId}/vote` (LIKE/DISLIKE),
`DELETE .../vote`, `GET .../my-vote`. Окно принимает голоса в
`[opensAt, closesAt)`; после дедлайна — 409 `VOTING_CLOSED`. Дельты счёта:
новый голос +1/−1, смена знака ±2, повтор 0, удаление — компенсация.
Гость (`X-Guest-Token`) голосует только в глобальных окнах: лимит 30/мин
на сессию (429 `RATE_LIMITED` + `Retry-After`); закрытые турниры скрыты
от гостя (404). Самоголосование запрещено (403).

## Лента и гостевые сессии (feed)

- `POST /api/v1/guest-sessions` — публично; токен возвращается один раз,
  передаётся в `X-Guest-Token`. Лимит выдачи — 10/мин на IP (429).
- `GET /api/v1/feed?limit=20&cursor=...` — пользователь либо гость (только
  глобальные карточки). Ответ `{items, nextCursor, hasNext}` без total —
  keyset-курсор с HMAC (`FEED_CURSOR_SECRET`). Невалидный курсор — 400
  `FEED_CURSOR_INVALID`, истёкший (TTL 1h) — 410 `FEED_CURSOR_EXPIRED`.
- Карточки: активные открытые окна, живые растения, чужие entry, ещё не
  оценённые субъектом (оценённые и свои исключаются read-портами
  tournaments — `docs/api-contracts/tournaments-internal.md`); порядок —
  от серверного seed (ADR-002).

## Архитектура (лаба №2)

7 сервисов по bounded contexts + eureka/config/gateway, межсервисные вызовы —
Feign через Eureka, события — HTTP-команды и polling (ADR-015), ошибки —
единый `ApiError` поверх Feign (ADR-018), демо-идентификация сквозь сервисы
(ADR-016), общие модули shared-lib/web/webflux (ADR-017), один postgres с
БД на сервис (ADR-019). Стеки: MVC+JPA (identity, media, plants, moderation),
WebFlux+JPA через `Blocking` (tournaments), R2DBC (geo), WebFlux+JdbcClient
(feed) — ADR-014. Подробности и диаграммы: `docs/architecture.md`.

## Готовность лабы №2

- [x] 13 модулей собираются: `./gradlew build` зелёный (unit + IT на
      Testcontainers)
- [x] `docker compose up --build` из чистого состояния: postgres (7 БД),
      eureka, config, gateway, 7 сервисов; health-чеки зелёные
- [x] Публичный API через gateway `:8080` не изменился — Bruno-коллекция
      `PlantArena API` проходит без правок
- [x] Межсервисные контракты задокументированы: `docs/api-contracts/`
- [x] Архитектура и решения: `docs/architecture.md`, ADR-014..019
- [x] CI на Gradle: `.github/workflows/ci.yml`

## Git workflow

- Ветки: `feat/...`, `fix/...`, `test/...`, `docs/...`, `refactor/...`
- Conventional Commits; тесты коммитируются вместе с реализацией или раньше
- `main` всегда зелёный (`./gradlew build`); push в удалённый `main` — только по явной команде
