# Дизайн итерации 7: глобальный турнир и geo

Дата: 2026-09-27. Статус: предложен. Источник: `docs/requirements.md`
(разделы 8, 2, 6, 11, 12.3, 13), `docs/plans/2026-09-23-roadmap.md` (итерация 7).

## Решения

1. **`GlobalCompetition` — единственная запись `tournament` типа GLOBAL**
   (раздел 11 разрешает): фиксированный UUID
   `00000007-10ba-4000-8000-000000000001`, статус RUNNING навсегда,
   `Tournament.finish()` для GLOBAL — ошибка состояния (инвариант «GLOBAL
   никогда не FINISHED» в домене). Запись создаётся идемпотентным
   bootstrap-раннером при старте (`EnsureGlobalCompetitionUseCase`),
   creator — системный UUID (якорь, не человек). Параметры private-режима
   (дедлайн, minParticipants) для GLOBAL — заглушки; тайминги глобального
   режима — в конфигурации, не в БД. GLOBAL скрыт из `GET /tournaments` и
   `GET /tournaments/{id}` (404): его публичное представление — `GET /global`.
2. **Тайминги и кластеризация — конфигурация** (`GlobalCompetitionSettings`,
   префикс `plantarena.global`): `epoch-duration` (по умолчанию PT24H),
   `final-window-duration` (по умолчанию PT6H); точность geohash —
   `plantarena.geo.geohash-precision` (по умолчанию 4, диапазон 1–12) в
   `GeoClusteringSettings` контекста geo. Версия политики —
   `geohash-v1-p<precision>`, фиксируется в каждом `ClusterSnapshot`.
3. **geo — Upstream для tournaments** (раздел 4.3): домен получает координаты
   во входной команде, identity не читает. `Geohash.encode` — чистая функция
   (base32, канонический алгоритм, эталонные векторы в тестах);
   `ClusteringPolicy`/`GeohashClusteringPolicy` группирует участников по
   ячейке; агрегат `ClusterSnapshot` (epochId, clusterKey, policyVersion,
   состав с locationVersion) неизменен после фиксации. Опубликованный
   контракт `geo.api.ClusterAssignment.assignClusters(epochId, members)` —
   команда: фиксирует снимки и возвращает `(snapshotId, clusterKey,
   entryIds)` по кластерам. Схема `geo` (V2): `cluster_snapshot` +
   `cluster_member` (раздел 11).
4. **`QualificationEpoch` — агрегат tournaments**: id, sequence, статус
   OPEN/CLOSED, `[opensAt, closesAt)`. Открывается только при наличии QUEUED-
   заявок (пустые эпохи не создаются — poller повторяет). Уникальность «одна
   OPEN-эпоха» — частичный уникальный индекс. Состав эпохи = её окна
   (координаты берутся на открытии; обновление геопозиции применяется к
   следующему отбору — раздел 8).
5. **Окно получает scope** (раздел 11): `voting_window.scope` ∈
   {PRIVATE, QUALIFICATION, FINAL} + `epoch_id`/`cluster_id`/`cluster_key`
   (кластер денормализован из ответа geo — публичное «обобщённое описание
   кластера»). Квалификационное окно — одно на непустой кластер эпохи
   (частичный UNIQUE (epoch_id, cluster_id)); финальное — последовательность
   на глобальном турнире (частичный UNIQUE (tournament_id, sequence) WHERE
   scope='FINAL'); «одно OPEN-окно на турнир» остаётся только для PRIVATE и
   для FINAL (частичные индексы). Глобальные окна допускают единственного
   участника (алгоритмы 4 и 7). `window_participant.result` расширяется
   PROMOTED (top-1 квалификации).
6. **Глобальные статусы entry** (раздел 11): QUEUED → QUALIFYING →
   FINAL_PENDING → FINALIST → ELIMINATED; WITHDRAWN только из QUEUED;
   QUALIFYING → ELIMINATED допустим. Переходы — методы `TournamentEntry` с
   проверкой состояния. Один активный global entry на пользователя —
   частичный уникальный индекс по активным статусам (допущение 5); уникальность
   private-пары (tournament_id, user_id) становится частичной по private-
   статусам (история global-участий не конфликтует).
7. **Подача заявки** `POST /global/entries {plantId}`: идентифицированный
   пользователь; растение — своё и APPROVED (первая версия принимает только
   одобренные, раздел 6) иначе 409 PLANT_NOT_APPROVED / 404; координаты в
   профиле обязательны иначе 409 LOCATION_REQUIRED; активное global-участие
   отсутствует иначе 409 GLOBAL_ENTRY_ACTIVE; резерв изображения —
   `PlantEligibility.reserveSubmission` с idempotency key = entryId, в одной
   tx (ADR-010). Снятие `DELETE /global/entries/{id}`: только владелец и
   только QUEUED (иначе 409 ENTRY_IN_WINDOW) → WITHDRAWN + освобождение
   резерва.
8. **Границы глобального времени — один use case
   `AdvanceGlobalCompetitionUseCase.advance(now)`** для scheduler'а
   (`GlobalBoundaryPoller`, fixedDelay 2с) и demo-ручки. Фиксированный
   порядок (алгоритм 6): (1) закрыть due-квалификационные окна; (2) закрыть
   due-финальное окно; (3) открыть следующее финальное (выжившие + все
   FINAL_PENDING → FINALIST; не открывается при отсутствии кандидатов и при
   уже открытом); (4) открыть следующую эпоху (QUEUED → кластеризация →
   QUALIFYING + окна по кластерам). Каждый шаг/окно — короткая tx (ADR-011);
   идемпотентность — по статусам (CLOSED-окно и OPEN-эпоха/финал — no-op),
   рестарт не убивает растения повторно (раздел 12.3).
9. **Закрытие квалификации** (алгоритмы 3–5): рейтинг
   score DESC, joinedAt ASC, entryId ASC; top-1 — PROMOTED, entry →
   FINAL_PENDING (резерв сохраняется до итога финала); остальные —
   ELIMINATED, гибель + COOLDOWN 24 ч (`PlantLifecycleGateway` уже
   поддерживает) + освобождение резерва + `EntryEliminated`. Единственный
   участник ячейки проходит без голосов (алгоритм 4). Эпоха закрывается,
   когда закрыты все её окна. **Закрытие финала** (алгоритмы 7–8): n ≥ 2 —
   выбывает `max(1, floor(n/2))` худших (гибель + COOLDOWN + освобождение);
   выжившие — SURVIVED, остаются FINALIST; n = 1 — лидер остаётся FINALIST
   (SURVIVED без выбывания); n = 0 — финальное окно не создаётся, финал
   ждёт. События — `EntryEliminated` (переиспользуется), новых опубликованных
   событий нет.
10. **Права голоса по scope**: PRIVATE — участник, допущенный к старту
    (без изменений, итерация 6); QUALIFICATION/FINAL — любой
    идентифицированный пользователь (раздел 2: голосовать в глобальном —
    всем USER+; гость — 401 до итерации 8); самоголосование — 403 в обоих
    режимах (домен). my-vote — те же правила.
11. **REST (раздел 13)**: `GET /global` (публично: тайминги, текущая эпоха,
    текущее финальное окно); `POST /global/entries` (201 + Location);
    `GET /me/global-entry` (активное участие или 404); `DELETE
    /global/entries/{id}` (204); `GET /global/clusters` (кластеры текущей
    эпохи: clusterId=snapshotId, clusterKey, windowId, memberCount, closesAt;
    публично, X-Total-Count); `GET /global/leaderboard?scope=FINAL` (только
    FINAL, иначе 400; публично); `GET /global/clusters/{id}/leaderboard`
    (открытое окно кластера, иначе 404). Лидерборд всегда содержит scope,
    windowId, closesAt, asOf (алгоритм 9); очки разных окон/кластеров не
    смешиваются. operationId — префикс `global-`.
12. **Порядок задач — persistence раньше application-сервисов** (отступление
    от «домен → application → адаптеры» внутри итерации, урок итераций 5–6):
    @Service-бины application требуют JPA-реализаций новых портов, иначе
    контекст IT не стартует между задачами. Поэтому: домен (Task 4) →
    миграции/JPA/порты (Task 5) → application + out-адаптеры (Task 6) →
    in-адаптеры и зелёный приёмочный (Task 7). Каждый коммит — зелёный
    `./mvnw verify`.
13. **identity расширяет OHS**: `UserDirectory.findLocation(userId)` →
    `(latitude, longitude, locationVersion)`; точные координаты наружу через
    REST по-прежнему не публикуются (только внутренним потребителям через
    api). tournaments потребляет через ACL
    `ParticipantLocationsGateway` (Consumer-driven порт).
