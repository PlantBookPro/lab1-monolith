# ADR-012: глобальный турнир — запись-якорь, эпохи и границы времени

Дата: 2026-09-28. Статус: принято (итерация 7). Контекст: разделы 8, 11, 12.3
требований.

## Решение

1. `GlobalCompetition` хранится как единственная запись `tournament` типа
   GLOBAL с фиксированным UUID `00000007-10ba-4000-8000-000000000001`
   (создаётся идемпотентным bootstrap-раннером, creator — системный UUID).
   Инвариант «GLOBAL никогда не FINISHED» — в домене (`Tournament.finish`
   кидает исключение). Публичное представление — `GET /global`; из
   `/tournaments` GLOBAL скрыт (404).
2. Временные границы глобального режима — один use case
   `AdvanceGlobalCompetitionUseCase.advance(now)` с фиксированным порядком
   (алгоритм 6): закрыть due-квалификацию → закрыть due-финал → открыть
   следующий финал → открыть следующую эпоху. Scheduler (fixedDelay 2с) и
   demo-ручка вызывают один и тот же use case.
3. Отступление «одна tx — один агрегат» (работает только в монолите):
   закрытие квалификационного/финального окна — окно + entries + команды
   plants (гибель COOLDOWN 24 ч, освобождение резерва) + события в одной tx;
   открытие эпохи — epoch + entries + команда geo + окна в одной tx.
   Растения обрабатываются в устойчивом порядке по plantId (раздел 12.3).
   План лабы №2: saga с идемпотентными командами (reservationId, статус
   окна как idempotency key); лабы №4 — outbox/inbox.
4. Идемпотентность: закрытие — по статусу окна (CLOSED — no-op), открытие
   финала/эпохи — по наличию OPEN-записи (частичные уникальные индексы
   `voting_window_one_open_final_uidx`, `qualification_epoch_one_open_uidx`).
   Рестарт приложения не теряет окна и не убивает растения повторно
   (гарантирует `GlobalIdempotencyIT`).
5. geo — Upstream: координаты приходят командой `ClusterAssignment`
   (кластеризация + неизменные снимки), identity читает только tournaments
   через `UserDirectory.findLocation` (ACL `ParticipantLocationsGateway`).
   Тайминги — `plantarena.global.*`, точность geohash —
   `plantarena.geo.geohash-precision`; версия политики фиксируется в снимках.
6. Уникальность участий: private-пара (tournament_id, user_id) — частичный
   индекс по private-статусам; одно активное глобальное участие на
   пользователя — частичный индекс по QUEUED/QUALIFYING/FINAL_PENDING/FINALIST
   (допущение 5: после гибели можно сразу подать другое изображение).
