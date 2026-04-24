# Plant Arena — Лабораторная №1 (модульный монолит)

Турниры растений: профили, загрузка изображений с автоматической модерацией
(ONNX), закрытые турниры с приглашениями, постоянный глобальный турнир с
географическим отбором, голосование, лента.

- Исходные требования: `docs/requirements.md` (полная спецификация)
- Задание лабораторной: `docs/assignment-lab1.md`
- Дизайн и решения: `docs/specs/`, `docs/domain/adr/`
- Дорожная карта итераций: `docs/plans/2026-09-23-roadmap.md`

## Запуск

```bash
docker compose up --build
# Swagger UI: http://localhost:8080/swagger-ui/index.html
```

## Демо-идентификация (ADR-005)

В профилях `dev`/`test` пользователь передаётся заголовком `X-Demo-User-Id: <UUID>`
(см. Swagger UI). Отсутствие заголовка = гость; неизвестный/деактивированный ID —
ошибка 401; роли всегда берутся из БД. Это механизм демонстрации, не аутентификация.
Обычный профиль без адаптера идентификации падает при старте с явной ошибкой.
Bootstrap-админ: `BOOTSTRAP_ADMIN_EMAIL` + `BOOTSTRAP_ADMIN_PASSWORD` (ENV),
повторный запуск дубликат не создаёт.

## Файлы (media)

Загрузка изображения — `POST /api/v1/files` (multipart, часть `file`): JPEG/PNG,
до 10 MiB и 20 млн пикселей, формат проверяется по фактическому содержимому.
Ответ: `assetId` и метаданные (без внутренних путей хранилища). Повторная загрузка
тех же байтов создаёт новый asset. Отпечаток изображения (алгоритм v1) игнорирует
метаданные и альфа-канал — ADR-007; эталонные примеры: `src/test/resources/media/reference`.

- Скачивание — `GET /api/v1/files/{id}`: владелец — всегда; чужим файл доступен
  только через одобренное растение (ADR-008, итерация 3).
- Удаление — `DELETE /api/v1/files/{id}`: владелец или админ; задействованный
  растением файл — 409 `ASSET_IN_USE` (итерация 3).

Локальное хранилище: каталог `MEDIA_STORAGE_ROOT` (по умолчанию `./storage/media`,
в Docker Compose — volume `storage`). Проверки: `./mvnw verify`.

## Растения (plants)

Подача заявки — `POST /api/v1/plants` (`assetId` своего файла + `title`): создаёт
растение PENDING/ALIVE, задействует файл (один файл — одно неархивированное
растение, ADR-008) и публикует `PlantSubmitted` (модерация — итерация 4).
Повторное использование запрещённого изображения — 409 `IMAGE_RESTRICTED`:
навсегда после поражения в закрытом турнире, на 24 часа после глобального
(`retryAt` — срок истечения суточного).

- Список — `GET /api/v1/plants[?ownerId=]`: свои — все статусы, чужие — только
  APPROVED; пагинация `page`/`size`, заголовок `X-Total-Count`.
- Просмотр — `GET /api/v1/plants/{id}`: владелец/админ всегда; чужие — только
  одобренные (скрытое — 404).
- Переименование — `PATCH /api/v1/plants/{id}`: только владелец, только `title`.
- Архивация — `DELETE /api/v1/plants/{id}`: только владелец, вне активного
  резерва турнира (409 `PLANT_UNDER_RESERVATION`, резервы — итерация 5);
  скрывает растение и освобождает файл.
- Статус модерации — `GET /api/v1/plants/{id}/moderation`: только владелец.

Одобрение модерации делает файл растения публично видимым чужим
(`GET /api/v1/files/{id}`); архивация возвращает приватность (ADR-008).

## Модерация (moderation)

Поданная заявка проходит автоматическое распознавание (ADR-009): MobileNetV2
(ONNX Runtime) внутри монолита. Зелёное на фото — не гарантия: решает
классификатор; ошибка распознавателя — не решение, заявка остаётся PENDING и
будет повторена (backoff 1с → 2с → … → кап 1ч, без лимита попыток).

- Модель (~13 МБ) скачивается автоматически при сборке в
  `target/models/mobilenetv2-1.0.onnx` (offline-сборка не ломается: задания
  честно уходят в RETRY, в логе видно `ClassifierUnavailableException`).
- Ручной запуск: `MODERATION_MODEL_PATH=... ./mvnw spring-boot:run`.
- Настоящий инференс на эталонных фото (ромашка/собака):
  `./mvnw verify -P inference` (в обычный `verify` не входит).
- Решение видно владельцу: `GET /api/v1/plants/{id}/moderation` — статус,
  причина (`PLANT_DETECTED`/`NOT_A_PLANT`) и `retryUploadAllowed`.

## Турниры (tournaments)

Закрытые (PRIVATE) турниры с приглашениями (раздел 7): модератор/админ создаёт
DRAFT (параметры + теги), приглашает пользователей; приём заявок — до дедлайна
регистрации. Принятие приглашения резервирует изображение растения (одно
изображение — один активный резерв, ADR-010): одобренное модерацией растение →
заявка READY сразу, идущая модерация → ACCEPTED_PENDING_MODERATION (решение
переводит в READY или возвращает в INVITED). Наступивший дедлайн стартует
турнир scheduler'ом (fixedDelay 2с) или вручную — один use case: READY ≥
minParticipants → RUNNING (участия ACTIVE, не-READY → EXPIRED, резервы
подтверждены); иначе CANCELLED с причиной INSUFFICIENT_PARTICIPANTS и
освобождением резервов (растения не погибают).

- Создание и теги — `POST /api/v1/tournaments`, `POST /api/v1/tags` (M/A);
  список доступных — `GET /api/v1/tournaments?status=&tagId=` (X-Total-Count).
- Приглашения — `POST /api/v1/tournaments/{id}/invitations`,
  `GET /api/v1/me/invitations`, `POST /api/v1/invitations/{id}/accept|decline`.
- Старт/отмена — `POST /api/v1/tournaments/{id}/start|cancel`; участники —
  `GET /api/v1/tournaments/{id}/entries`.
- Диагностика (dev/test, M/A): `POST /api/v1/internal/demo/jobs/run-due` —
  тот же use case, что scheduler, без обхода правил; ответ — `processed`
  (стартовавшие/отменённые турниры) и `closedWindows` (закрытые окна).
- Старт создаёт первый раунд `VotingWindow` (sequence 1, длительность
  roundDuration, счёт с нуля). Закрытие по дедлайну — scheduler (fixedDelay
  2с) или demo-ручка. Выбывание: `min(n−1, max(1, floor(n·f)))` худших по
  рейтингу (score DESC, joinedAt ASC, entryId ASC); выбывшие — гибель +
  PERMANENT-запрет + освобождение резерва. Один выживший — WINNER, турнир
  FINISHED, резерв победителя освобождён; иначе — следующий раунд
  (sequence + 1, выжившие, счёт с нуля).
- Раунды и итоги — `GET /api/v1/tournaments/{id}/rounds|leaderboard|results`
  (организатор/админ или приглашённый/участник; X-Total-Count).

## Глобальный турнир (global)

Постоянный глобальный турнир (раздел 8): заявка `POST /api/v1/global/entries`
(своё APPROVED-растение, координаты в профиле обязательны — `PUT /me/location`;
одно активное участие на пользователя) → очередь → эпоха отбора раз в
`plantarena.global.epoch-duration` (по умолчанию 24 ч): гео-кластеры
(geohash, `plantarena.geo.geohash-precision`), квалификационное окно на
непустой кластер. Закрытие: top-1 — в финал (PROMOTED), остальные — гибель +
суточный запрет совпавшей картинки (COOLDOWN 24 ч, `retryAt`) + освобождение
резерва. Финал — непрерывные окна `final-window-duration`: из n ≥ 2 выбывает
max(1, floor(n/2)) худших, единственный лидер остаётся; новых финалистов
включает следующее окно. Порядок одновременных границ фиксирован (алгоритм 6);
scheduler идемпотентен — рестарт не убивает повторно (ADR-012).

- Конфигурация и текущие окна — `GET /api/v1/global` (публично).
- Кластеры эпохи — `GET /api/v1/global/clusters`; отбор кластера —
  `GET /api/v1/global/clusters/{id}/leaderboard`; финал —
  `GET /api/v1/global/leaderboard?scope=FINAL` (scope, windowId, closesAt, asOf).
- Своё участие — `GET /api/v1/me/global-entry`; снятие из очереди —
  `DELETE /api/v1/global/entries/{id}` (только QUEUED, иначе 409).
- Голосовать в глобальных окнах может любой идентифицированный пользователь
  (гость — итерация 8); самоголосование запрещено.
- Диагностика: `POST /api/v1/internal/demo/jobs/run-due` дополнительно
  продвигает границы (ответ: globalQualificationClosed/globalFinalClosed/
  globalFinalsOpened/globalEpochsOpened).

## Голосование (voting)

`PUT /api/v1/windows/{windowId}/entries/{entryId}/vote` (LIKE/DISLIKE),
`DELETE .../vote`, `GET .../my-vote` — текущий голос субъекта. Голосовать
может участник, допущенный к старту (выбывший — тоже, допущение 9);
постороннему турнир скрыт (404), организатор-не-участник — 403, гость — 401
(GUEST-субъект — итерация 8). Самоголосование запрещено (403, допущение 6).
Окно принимает голоса в интервале `[opensAt, closesAt)`; после дедлайна —
409 `VOTING_CLOSED`. Дельты счёта: новый голос +1/−1, смена знака ±2, повтор 0,
удаление — компенсация.

## Проверка (единственная команда)

```bash
./mvnw verify
```

Запускает: unit- и application-тесты (Surefire), ArchUnit-правила границ,
интеграционные/приёмочные тесты на Testcontainers PostgreSQL (Failsafe),
отчёт и gate JaCoCo (LINE ≥ 70%). Настоящий ONNX-инференс — отдельно:
`./mvnw verify -P inference`.

## Git workflow

- Ветки: `feat/...`, `fix/...`, `test/...`, `docs/...`, `refactor/...`
- Conventional Commits; тесты коммитируются вместе с реализацией или раньше
- `main` всегда зелёный (`./mvnw verify`); push в удалённый `main` — только по явной команде

## Архитектура

Один Maven-модуль, bounded contexts по DDD (пакеты `identity`, `media`, `plants`,
`moderation`, `tournaments`, `geo`, `feed` + технический `shared` + `config`).
Границы проверяются ArchUnit-тестами (`src/test/java/com/plantarena/architecture/`).
Одна PostgreSQL, схема на контекст, миграции Flyway по каталогам контекстов.
Подробности: `docs/domain/context-map.md`, `docs/domain/aggregates.md`.

## Процедура выделения контекста в сервис (готовность к лабе №2)

1. Создать новый Spring Boot проект и перенести пакет `<context>` целиком
   и нужные части `shared`.
2. Перенести схему и каталог миграций `db/migration/<context>` без изменений.
3. `api`-фасады превратить в REST-контроллеры — DTO уже являются контрактом.
4. У потребителей заменить `adapter.out.<context>` (in-process) на Feign-адаптер
   с тем же портом.
5. Синхронные подписки на события заменить идемпотентными HTTP-командами
   с retry jobs (лаба №2) или outbox → Kafka → inbox (лаба №4).
6. Доменные и application-тесты переносятся без изменений и остаются зелёными;
   меняются только тесты адаптеров.
