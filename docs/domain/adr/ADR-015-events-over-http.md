# ADR-015: События поверх HTTP + polling для модерации

Дата: 2026-10-23. Статус: принято (лаба №2).

## Контекст

В монолите события между контекстами доставлялись in-process
(`@EventListener` в той же tx — ADR-010/011). В микросервисах общей
транзакции и общего брокера нет; Kafka — план лабы №4, не №2.

## Решение

- **`PlantModerationDecided` (plants → tournaments)**: издатель —
  `@TransactionalEventListener(AFTER_COMMIT)`-relay в plants-service,
  доставляет Feign `POST /internal/events/plant-moderation-decided`.
  Команда **идемпотентна по состоянию заявки**: повтор (после дедлайна,
  отмены, терминальных статусов, уже применённого решения) — no-op.
- **Проекции окон (tournaments → feed)**: `POST /internal/feed/projections`
  / `DELETE /internal/feed/projections/{windowId}` — идемпотентны по
  windowId (создание/удаление карточек).
- **`PlantSubmitted` (plants → moderation) — polling**: moderation-service
  опрашивает `GET /internal/plants?moderationStatus=PENDING` (fixedDelay
  5с, пачка ≤50); создание задания идемпотентно по plantId. Retry заданий —
  внутренний poller (fixedDelay 2с, backoff из лабы №1).
- Сага резервов (tournaments → plants): reserve → confirm/release с
  компенсацией при сбое, идемпотентность резерва — свежий ключ на попытку
  (ADR-010).

## Последствия

- AFTER_COMMIT-relay **без outbox**: событие может потеряться при падении
  между commit и POST. Риск принят: команды идемпотентны, потеря
  компенсируется повтором бизнес-действия; для модерации polling
  самовосстанавливается. Честная замена — outbox (лаба №4).
- Синхронные связки «одна tx — два контекста» из лабы №1 стали
  saga/компенсациями: сбой подтверждения резерва откатывает заявку.
- Лаба №4: outbox → Kafka (`plant.moderation.v1`, `plant.lifecycle.v1`)
  → inbox; relay и poller удаляются, контракты команд становятся событиями.
