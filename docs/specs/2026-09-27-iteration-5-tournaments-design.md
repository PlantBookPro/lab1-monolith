# Дизайн итерации 5: tournaments (private) — закрытые турниры

Дата: 2026-09-27. Статус: согласован. Источник: `docs/requirements.md`
(разделы 7, 11, 12, 13), `docs/plans/2026-09-23-roadmap.md` (итерация 5).

## Решения (согласованы)

1. **Старт — один use case для ручки и scheduler'а**:
   `StartTournamentUseCase.start(actor, tournamentId)` (ручка организатора,
   только после дедлайна) и `startDue(now, limit)` (scheduler
   `adapter.in.jobs` и demo-ручка `POST /internal/demo/jobs/run-due`).
   Идемпотентность — по статусу: повторный вызов для не-`REGISTRATION_OPEN`
   — no-op. `startDue` берёт due-турниры из БД (`status = REGISTRATION_OPEN
   AND registration_deadline <= now`) и запускает каждый в отдельной
   короткой tx (TransactionTemplate) — один битый турнир не блокирует
   остальные.
2. **Первый VotingWindow — итерация 6.** Итерация 5 доводит турнир до
   `RUNNING` и создаёт `TournamentEntry` для READY-заявок; окна, выбывание,
   `FINISHED` и `EntryEliminated`/`TournamentFinished` — итерация 6 (раздел 7
   «при старте создаётся первый раунд» замыкается там, где появляется агрегат
   окна). State machine турнира (`DRAFT → REGISTRATION_OPEN → RUNNING →
   FINISHED`, `DRAFT/REGISTRATION_OPEN → CANCELLED`) фиксируется в домене
   уже сейчас.
3. **Ключ идемпотентности резерва — на попытку принятия.** Каждое принятие
   приглашения генерирует свежий UUID-ключ для
   `PlantEligibility.reserveSubmission` (повторное принятие после REJECTED —
   новый резерв, тот же ключ вернул бы старый reservationId). Идемпотентность
   HTTP-повтора `accept` — по состоянию: повтор с тем же `plantId` для уже
   принятого приглашения возвращает текущий статус без нового резерва.
4. **EXPIRED и отмена.** При старте все не-READY приглашения
   (`INVITED`, `ACCEPTED_PENDING_MODERATION`) → `EXPIRED`, резервы последних
   освобождаются; `DECLINED`/`REVOKED` не меняются. При отмене турнира
   статусы приглашений не меняются (отмена видна по турниру), резервы
   принятых заявок освобождаются. Поздний результат модерации не меняет
   терминальные `REVOKED`/`EXPIRED` и заявки отменённого турнира: реакция на
   `PlantModerationDecided` переводит в READY только при
   `REGISTRATION_OPEN` и `now < deadline`.
5. **Межконтекстные tx итерации 5 — ADR-010** («только в монолите», план
   saga для лабы №2): принятие приглашения (`Invitation` + резерв plants),
   старт (`Tournament` + `TournamentEntry` + подтверждение резервов +
   освобождение резервов при отмене), решение модерации → перевод заявки в
   READY синхронно в tx `recordDecision` (in-process `@EventListener`, как
   `PlantSubmitted` в итерации 4).
6. **Новые опубликованные контракты upstream:**
   - `identity.api.UserDirectory` (`findById → UserData(id, displayName,
     active)`) — первый потребитель tournaments (проверка пользователя при
     приглашении); identity пока не имела `api`-пакета.
   - `plants.api.PlantDirectory` (`findById → PlantData`) — read-контракт:
     tournaments проверяет APPROVED при принятии приглашения (PENDING →
     `ACCEPTED_PENDING_MODERATION`, APPROVED → сразу `READY`). Права не
     проверяются — внутренний контракт монолита (как `MediaAssets.loadContent`
     в итерации 4).
7. **Теги.** Справочник `tag` + M2M `tournament_tag` (PK по двум FK,
   `@ManyToMany` в JPA-модели, раздел 11). `tagIds` — параметры турнира,
   меняются только в `DRAFT`. Удаление используемого тега → 409.
8. **События.** Публикуются `TournamentStarted` и `InvitationCreated`
   (перечень `aggregates.md`); подписчики появятся в итерациях 6–8 и лабе №4.

## Архитектура и поток

Контекст `tournaments` — downstream от identity, plants (раздел 4.3); geo —
итерация 7.

```
POST /tournaments (M/A) → DRAFT (параметры + tagIds)
POST /{id}/invitations (организатор) → Invitation INVITED → InvitationCreated
POST /{id}/open-registration → REGISTRATION_OPEN
POST /invitations/{id}/accept {plantId} (адресат, now < deadline):
    PlantDirectoryGateway.findById → APPROVED?
    PlantEligibilityGateway.reserve(owner, plant, freshKey)  ── одна tx (ADR-010)
    → ACCEPTED_PENDING_MODERATION | READY
модерация (итерация 4) → PlantModerationDecided (in-process, та же tx):
    APPROVED + REGISTRATION_OPEN + now < deadline + резерв действителен
        (PlantEligibilityGateway.confirm) → READY
    REJECTED → rollback в INVITED + release резерва (история подачи сохранена)
дедлайн (scheduler / demo-ручка / POST /{id}/start — один use case):
    READY ≥ minParticipants → RUNNING: confirm каждого резерва,
        TournamentEntry.admit для каждого READY, не-READY → EXPIRED,
        TournamentStarted
    READY < minParticipants → CANCELLED (INSUFFICIENT_PARTICIPANTS),
        резервы освобождаются, растения не погибают
POST /{id}/cancel (до RUNNING) → CANCELLED + release резервов
```

## Домен

- `Tournament`: параметры валидируются фабрикой и `updateParameters`
  (только DRAFT); `start(now, readyCount)` требует `now ≥ deadline` и
  `readyCount ≥ minParticipants`; `cancel` — только до RUNNING; безопасное
  описание — в DRAFT/REGISTRATION_OPEN/RUNNING.
- `Invitation`: переходы раздела 7; `submittedPlantId` сохраняется как
  история последней подачи при возврате в INVITED.
- `TournamentEntry`: `admit` при старте (ACTIVE); ELIMINATED/WINNER —
  итерация 6.
- `Tag`: справочник с валидацией имени.

## Хранилище (схема `tournaments`, миграция V2)

`tournament` (version, index status+deadline для scheduler'а), `invitation`
(UNIQUE(tournament_id, user_id), version, FK → tournament), `tournament_entry`
(UNIQUE(tournament_id, user_id), FK → tournament), `tag` (UNIQUE(name)),
`tournament_tag` (PK по двум FK). Enum → VARCHAR + CHECK; маппинг явный.

## REST (раздел 13)

`/tournaments` (+open-registration/cancel/invitations/start/entries),
`/me/invitations`, `/invitations/{id}/accept|decline`, `/tags`,
`/internal/demo/jobs/run-due` (только dev/test). Page-списки с
`X-Total-Count`; скрытые турниры — 404; конфликты состояний/дедлайна — 409.

## Тестирование

Пирамида раздела 14: доменные unit (переходы/инварианты) → application
(in-memory фейки) → контрактные тесты репозиториев (fake + JPA/Testcontainers,
UNIQUE-пары) → приёмочный `TournamentsApiIT` (MockMvc + Testcontainers,
детерминированный классификатор как в итерации 4, короткие дедлайны +
Awaitility для scheduler'а). DoD итерации: сценарии раздела 7 через HTTP,
scheduler = та же ручка, зелёный `./mvnw verify`.
