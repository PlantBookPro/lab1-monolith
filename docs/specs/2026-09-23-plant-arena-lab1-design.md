# Plant Arena — Лабораторная №1: дизайн (модульный монолит)

Дата: 2026-09-23. Статус: одобренено.
Исходные требования: `docs/requirements.md` (полная спецификация, разделы 0–17).

## 1. Цель

Модульный монолит на Spring Boot в одном Maven-модуле: система турниров растений
с профилями, загрузкой изображений, автоматической модерацией, закрытыми и
постоянным глобальным турниром, голосованием и лентой. Границы bounded contexts
проверяются ArchUnit; любой контекст переносится в отдельный сервис в лабе №2
копированием пакета без переписывания домена и use cases.

## 2. Стек (совместимость проверена 2026-09-23)

| Компонент | Версия | Комментарий |
|---|---|---|
| Java | 21 | LTS |
| Spring Boot | 4.0.8 | Стабильная линия с OSS-патчами; совместима с Spring Cloud 2025.1.x (нужно в лабе №2) |
| Spring Cloud (закладка на лабу №2) | 2025.1.3 | Официально поддерживает Boot 4.0.x |
| springdoc-openapi | 3.1.1 | Ветка 3.x для Boot 4 |
| Testcontainers | 2.0.5 (BOM; модули `testcontainers-junit-jupiter`, `testcontainers-postgresql`) | |
| ArchUnit | 1.5.0 (`archunit-junit6` — Boot 4.0.8 управляет JUnit Jupiter 6.0.3) | |
| JaCoCo | 0.8.15 | pin в pom |
| PostgreSQL | 17.5 | image `postgres:17.5-alpine` в Compose (тег проверен) |
| ONNX Runtime | 1.30.0 | pin (проверено на Maven Central 2026-09-23); классификатор растений в JVM |
| Flyway | 11.14.1 | управляется BOM Boot; + `flyway-database-postgresql` |
| Docker-образы | `maven:3.9.11-eclipse-temurin-21`, `eclipse-temurin:21-jre` | теги проверены | |

Boot 4.1 не выбран: для него ещё нет Spring Cloud train. Boot 3.5 не выбран:
OSS-EOL июнь 2026.

## 3. Архитектура

- Корневой пакет `com.plantarena`; контексты: `identity`, `media`, `plants`,
  `moderation`, `tournaments`, `geo`, `feed`; плюс `shared` (только техническое:
  security, web, event-конверт) и `config` (единственное место, знающее несколько
  контекстов).
- Внутри контекста: `api` (опубликованный контракт: фасады, DTO, events) /
  `domain` (агрегаты, VO, порты репозиториев) / `application` (use cases,
  выходные порты) / `adapter` (`in.web`, `in.events`, `in.jobs`,
  `out.persistence`, `out.<ctx>` ACL).
- Межконтекстные взаимодействия: синхронные — порт потребителя → ACL-адаптер →
  `api`-фасад поставщика; асинхронные — опубликованные события
  (`<context>.api.event`) через in-process `IntegrationEventPublisher`.
  Обязательные последствия (заявка → READY, гибель при закрытии окна) —
  синхронно в той же транзакции.
- Направления зависимостей — строго по context map из требований (раздел 4.3);
  ArchUnit-тесты пишутся в итерации 0 (9 правил раздела 10.2 + тест на каждое
  запрещённое направление).
- Spring Modulith не используется (только ArchUnit) — ADR-004.

## 4. Ключевые решения (ADR)

1. **ADR-001 Classifier**: ONNX Runtime 1.30.0 + MobileNetV2 (ImageNet-1000), файл
   модели закоммичен в репо (зафиксированная версия). Решение: сумма
   вероятностей plant-классов ImageNet ≥ порога (порог конфигурируемый,
   значение фиксируется в ADR). Инференс вне DB-транзакции; недоступность
   модели → PENDING + retry (техническая ошибка ≠ REJECTED).
2. **ADR-002 Feed**: собственная read-модель (схема `feed`, таблица карточек),
   обновляемая опубликованными событиями. Псевдослучайный sort key вычисляется
   в SQL от server-issued seed; keyset-курсор (FeedCursor) защищён HMAC.
   Эволюционирует в лабе №4 в outbox → Kafka → проекцию.
3. **ADR-003 Flyway**: у каждого контекста свой каталог
   `db/migration/<context>` и своя `flyway_schema_history` в своей схеме
   (несколько Flyway-бинов в `config`) — миграции переносятся в сервис лабы №2
   без переименований.
4. **ADR-004 Без Spring Modulith**: все архитектурные правила покрываются
   обязательными ArchUnit-тестами; меньше зависимостей и рисков.
5. **ADR-005 Демо-идентификация**: `X-Demo-User-Id` только в профилях dev/test;
   отсутствие заголовка = гость; обычный профиль без адаптера идентификации —
   явная ошибка конфигурации при старте. Механизм описан в Swagger.

Остальные допущения (раздел 3 требований) фиксируются в `docs/domain/adr/`
в итерации 0 как предлагаемые допущения.

## 5. Данные

- Одна PostgreSQL, схемы по контекстам: `identity`, `media`, `plants`,
  `moderation`, `tournaments`, `geo`, `feed`. Межсхемных FK нет.
- Enum → VARCHAR + CHECK; JPA `EnumType.STRING`.
- Partial unique indexes: один активный глобальный entry на пользователя; один
  активный резерв (ownerId, fingerprint); один голос на (window, entry, subject).
- `version` (optimistic locking) на конкурентных агрегатах.
- JPA-сущности только в `adapter.out.persistence` своего контекста; маппинг на
  домен явный; Hibernate `ddl-auto=validate`.

## 6. Тестирование

Пирамида (раздел 14.2 требований): ArchUnit → доменные unit (без Spring,
фиксированный Clock) → application (in-memory fakes) → контрактные тесты
репозиториев (abstract-класс: fake и JPA+Testcontainers) → `@DataJpaTest` +
Testcontainers → приёмочные MockMvc + Testcontainers → конкурентные
(CountDownLatch, реальный PostgreSQL). `./mvnw verify` запускает всё;
JaCoCo merge + report + check, совокупный LINE coverage ≥ 70%.

## 7. План итераций

Каждая итерация: TDD (падающий приёмочный тест → внутренний цикл → зелёный
verify), заканчивается коммитом (Conventional Commits, ветки `feat/...`).

| # | Содержание |
|---|---|
| 0 | Скелет: Maven Wrapper, пакеты, ArchUnit-правила, Testcontainers, Flyway по схемам, ProblemDetail, Clock-бин, Docker Compose, docs (глоссарий, context map, ADR) |
| 1 | identity: bootstrap ADMIN, создание USER модератором, роли, профиль, координаты, CurrentActor через X-Demo-User-Id |
| 2 | media: загрузка, валидация формата/размеров, отпечаток с эталонными примерами, локальный FileStorage |
| 3 | plants: Plant, запреты, резервы с idempotency key, PlantEligibility, гибель |
| 4 | moderation: задания, retry, ONNX-адаптер + тестовый, применение решения, событие в tournaments |
| 5 | tournaments (private): state machine, приглашения, старт по дедлайну/вручную, отмена |
| 6 | Голосование и закрытие раундов: окна, голоса, дельты, блокировки, выбывание, конкурентные тесты |
| 7 | Глобальный турнир и geo: эпохи, кластеры, квалификация, финал, порядок границ, рестарт без повторной гибели |
| 8 | feed и гостевые сессии: keyset-лента с подписанным курсором, лимиты, 429 |
| 9 | Готовность: покрытие ≥70%, Swagger-сценарии, README, ER-диаграмма, демонстрация inference |

Чекпоинты review: после итераций 0, 4, 6, 9.

## 8. Критерии готовности

- `./mvnw verify` зелёный, покрытие ≥ 70% измерено gate-ом;
- ArchUnit подтверждает отсутствие циклов и запрещённых зависимостей;
- каждое допущение раздела 3 и инвариант `aggregates.md` покрыто именованным
  тестом;
- `docker compose up --build` поднимает систему из чистого состояния, все
  сценарии выполняемы через Swagger UI;
- inference продемонстрирован на положительном и отрицательном изображении;
- в `docs/`: глоссарий, context map, агрегаты, ADR, ER-диаграмма, процедура
  выделения сервиса.
