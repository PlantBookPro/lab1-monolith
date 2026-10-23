# ADR-019: Один контейнер PostgreSQL (БД на сервис) и config-repo на classpath

Дата: 2026-10-23. Статус: принято (лаба №2).

## Контекст

Нужна demo-инфраструктура для 7 сервисов: базы, конфигурация, запуск
одной командой. Отдельный контейнер Postgres на сервис и git-репозиторий
конфигурации — тяжелее самой лабы.

## Решение

- **Один контейнер PostgreSQL 17, база на сервис** (`identity`, `media`,
  `plants`, `moderation`, `tournaments`, `geo`, `feed`): создаётся
  `scripts/docker/init-databases.sql` (compose) или
  `scripts/local/create-databases.sql` (локальный dev). Изоляция данных
  на уровне БД (не схемы, как в монолите), доступ — свои учётки ENV
  `DB_USERNAME`/`DB_PASSWORD`.
- **config-repo на classpath config-server'а**
  (`config-server/src/main/resources/config-repo/<service>.yml`), backend
  — native, не git: конфигурация версионируется в этом же репозитории,
  отдельный git-репозиторий не нужен. Сервисы берут базовую конфигурацию
  из локального `application.yml`, переопределения — из config-server
  (`SPRING_CONFIG_IMPORT`).

## Последствия

- Отказ одного инстанса БД валит всех — принято для demo; продуктовый
  вид — инстанс (и учётка) на сервис.
- Config-repo на classpath не показывает историю изменений отдельно от
  кода — приемлемо: изменения конфигурации идут коммитами вместе с кодом.
- Локальный dev: поднять только postgres + eureka + config
  (`docker compose up postgres eureka-server config-server`), создать БД
  скриптом, затем `./gradlew :<svc>:bootRun`.
