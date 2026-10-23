# ADR-014: Reactive-стеки сервисов — WebFlux+JPA (tournaments), R2DBC (geo), JdbcClient (feed)

Дата: 2026-10-23. Статус: принято (лаба №2).

## Контекст

При выделении сервисов из монолита (Spring MVC + JPA) нужно было решить,
какой веб/доступ-стек берёт каждый сервис. Домен и application перенесены
из лабы №1 без изменений — переписывать JPA-репозитории на reactive
недопустимо по объёму и риску.

## Решение

- **tournaments — WebFlux + блочный JPA**: контроллеры reactive
  (`Mono`/`Flux`), вызовы домена оборачиваются `shared-webflux.Blocking.call`
  (boundedElastic). Домен/application/JPA/`@Transactional` — без изменений.
- **geo — полностью R2DBC**: лёгкий контекст без JPA-домена (снимки
  кластеров); end-to-end reactive. Flyway-миграции — JDBC (Flyway R2DBC
  не умеет), отдельный блочный URL.
- **feed — WebFlux + JdbcClient**: read-проекция без JPA-сущностей,
  keyset-курсор в SQL — JdbcClient достаточно, JPA не нужен.
- **identity, media, plants, moderation — Spring MVC + JPA**: перенесены
  как есть.

## Последствия

- Ни в одном сервисе нет двух драйверов одного стека (кроме JDBC-Flyway
  в geo — только для миграций).
- `Blocking` — осознанный компромисс: реактивность endpoints без
  переписывания JPA; полный reactive-путь — вопрос будущего, не лабы.
- Общие веб-механизмы разделены по стекам — ADR-017.
