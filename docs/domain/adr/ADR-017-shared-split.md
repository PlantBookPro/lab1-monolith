# ADR-017: Разделение shared на модули lib / web / webflux

Дата: 2026-10-23. Статус: принято (лаба №2).

## Контекст

В монолите `shared` — один пакет: security (CurrentActor, роли), web
(ApiError, обработчик исключений, TraceIdFilter), события. В лабе №2
сервисы на разных веб-стеках (ADR-014): servlet-стартеры и WebFlux
конфликтуют в одном classpath, общий `shared` тянул бы оба.

## Решение

Три Gradle-модуля вместо одного:

- **`shared-lib`** — без веб-стека: `security` (CurrentActor, AppRole,
  исключения), `web/ApiError` (запись-контракт ошибок, без Spring MVC),
  `event`. Зависят все сервисы.
- **`shared-web`** — servlet: `ApiExceptionHandler`, `TraceIdFilter`,
  конфигурация. Зависят MVC-сервисы (identity, media, plants, moderation).
- **`shared-webflux`** — reactive: `Blocking` (ADR-014),
  `ReactiveApiExceptionHandler`, `ReactiveCurrentActorProvider`,
  `TraceIdWebFilter`. Зависят WebFlux-сервисы (tournaments, geo, feed).

## Последствия

- ApiError живёт в `shared-lib` — Feign-клиенты любого стека декодируют
  его одинаково (ADR-018).
- `monolith-reference` сохраняет лабу №1 как есть (свой `shared`).
- Дублирование servlet/reactive-обработчиков — цена двух стеков;
  альтернатива (все сервисы на одном стеке) отвергнута в ADR-014.
