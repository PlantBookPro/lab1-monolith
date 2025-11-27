# ER-диаграмма (схемы по контекстам)

Источник истины — миграции Flyway `src/main/resources/db/migration/<context>/`.
Одна PostgreSQL, отдельная схема на контекст (раздел 11 требований).
**Межсхемных FK нет** — ссылки между контекстами логические (UUID), ссылочная
целостность проверяется use cases: компромисс ради выделения сервисов в лабе №2
(перенос схемы = перенос каталога миграций без изменений). Все enum — VARCHAR +
CHECK; время — `timestamptz` (UTC); ID — UUID.

## identity

```mermaid
erDiagram
    APP_USER {
        uuid id PK
        varchar email_normalized UK "нормализуется доменом"
        varchar display_name
        varchar password_hash "только хэш, никогда в ответах"
        varchar status "ACTIVE | DEACTIVATED, CHECK"
        double latitude "NULL разрешён, CHECK -90..90"
        double longitude "NULL разрешён, CHECK -180..180"
        bigint version "optimistic locking"
    }
    ROLE {
        bigint id PK "GENERATED ALWAYS AS IDENTITY"
        varchar code UK "USER | MODERATOR | ADMIN, CHECK"
    }
    USER_ROLE {
        uuid user_id PK "FK -> app_user"
        bigint role_id PK "FK -> role"
    }
    USER_ROLE }o--|| APP_USER : user_id
    USER_ROLE }o--|| ROLE : role_id
```

Many-to-Many без дополнительных полей: `app_user ↔ role` через `user_role`
(PK по двум FK). Справочник `role` засеян миграцией.

## media

```mermaid
erDiagram
    MEDIA_ASSET {
        uuid id PK
        uuid owner_id "логическая ссылка -> identity.app_user"
        varchar storage_key UK
        varchar mime_type "image/jpeg | image/png, CHECK"
        bigint byte_size "CHECK 1..10485760 (10 MiB)"
        int width "CHECK > 0; лимит 20e6 px суммарно — домен"
        int height "CHECK > 0"
        varchar raw_sha256 "sha256 байтов"
        varchar image_fingerprint "нормализованные пиксели, ADR-007"
        int fingerprint_version
        timestamptz created_at
    }
    ASSET_CLAIM {
        uuid asset_id PK "FK -> media_asset, ON DELETE CASCADE"
        uuid plant_id "логическая ссылка -> plants.plant"
        boolean publicly_visible
        timestamptz claimed_at
    }
    ASSET_CLAIM ||--|| MEDIA_ASSET : asset_id
```

`asset_claim` — задействованность файла растением (ADR-008): один файл — одно
растение (PK по `asset_id`); media хранит, plants командует через
`MediaAssetClaims` (иначе цикл зависимостей). Индексы: `(owner_id, created_at)`,
`(image_fingerprint)`.

## plants

```mermaid
erDiagram
    PLANT {
        uuid id PK
        uuid owner_id "логическая -> identity.app_user"
        uuid asset_id "логическая -> media.media_asset"
        varchar fingerprint
        int fingerprint_version
        varchar title
        varchar moderation_status "PENDING | APPROVED | REJECTED, CHECK"
        varchar moderation_reason
        varchar life_status "ALIVE | DEAD, CHECK, DEAD необратим"
        timestamptz died_at
        timestamptz archived_at
        timestamptz created_at
        bigint version "optimistic locking"
    }
    IMAGE_RESTRICTION {
        uuid id PK
        uuid owner_id "логическая -> identity.app_user"
        varchar fingerprint
        int fingerprint_version
        varchar kind "PERMANENT | COOLDOWN, CHECK"
        timestamptz expires_at "NULL только для PERMANENT, CHECK"
        varchar reason
        uuid source_entry_id "логическая -> tournaments.tournament_entry"
        timestamptz created_at
    }
    PLANT_RESERVATION {
        uuid id PK
        uuid owner_id "логическая -> identity.app_user"
        uuid plant_id "логическая -> plants.plant (внутрисхемная, без FK)"
        varchar fingerprint
        int fingerprint_version
        uuid idempotency_key UK
        varchar status "ACTIVE | RELEASED, CHECK пары status-released_at"
        timestamptz created_at
        timestamptz released_at
    }
```

Физических FK в схеме нет (границы контекстов, раздел 10.4). Частичные
уникальные индексы (set-инварианты, проверяются БД):

- `plant_active_asset_uidx ON plant(asset_id) WHERE archived_at IS NULL` —
  один файл — одно неархивированное растение (ADR-008);
- `plant_reservation_active_pair_uidx ON plant_reservation(owner_id, fingerprint)
  WHERE status = 'ACTIVE'` — не более одного активного резерва на пару
  (допущение 4);
- `image_restriction(owner_id, fingerprint)` — обычный индекс, история запретов
  append-only;
- `plant(owner_id, created_at)` — обычный индекс, выборка растений владельца.

## moderation

```mermaid
erDiagram
    MODERATION_JOB {
        uuid id PK
        uuid plant_id "логическая -> plants.plant"
        uuid asset_id "логическая -> media.media_asset"
        varchar status "NEW | IN_PROGRESS | RETRY | DONE, CHECK"
        int attempts "retry без лимита, backoff кап 1ч"
        timestamptz next_attempt_at
        varchar model_version
        float confidence
        varchar reason_code "PLANT_DETECTED | NOT_A_PLANT | STALE, CHECK"
        timestamptz started_at
        timestamptz completed_at
        timestamptz created_at
        bigint version "optimistic locking"
    }
```

Частичный индекс due-опроса: `moderation_job_due_idx ON moderation_job(
next_attempt_at) WHERE status IN ('NEW','RETRY')`. Обычный индекс
`(plant_id, created_at)` — последнее задание по заявке (идемпотентность
слушателя). Ошибка распознавателя — не решение (RETRY), устаревший результат —
STALE.

## tournaments

```mermaid
erDiagram
    TOURNAMENT {
        uuid id PK "GLOBAL — фиксированный id, ADR-012"
        uuid creator_id "логическая -> identity.app_user"
        varchar name
        varchar description
        varchar type "PRIVATE | GLOBAL, CHECK"
        varchar status "DRAFT | REGISTRATION_OPEN | RUNNING | FINISHED | CANCELLED, CHECK"
        varchar algorithm "ROUND_ELIMINATION, CHECK"
        timestamptz registration_deadline
        bigint round_duration_seconds "CHECK > 0"
        double elimination_fraction "CHECK 0..1 исключительно"
        int min_participants "CHECK >= 2"
        varchar cancel_reason "INSUFFICIENT_PARTICIPANTS, CHECK"
        timestamptz created_at
        bigint version "optimistic locking"
    }
    INVITATION {
        uuid id PK
        uuid tournament_id FK
        uuid user_id "логическая -> identity.app_user"
        uuid invited_by "логическая -> identity.app_user"
        varchar status "INVITED | ACCEPTED_PENDING_MODERATION | READY | DECLINED | REVOKED | EXPIRED, CHECK"
        timestamptz invited_at
        timestamptz responded_at
        uuid submitted_plant_id "логическая -> plants.plant"
        uuid reservation_id "логическая -> plants.plant_reservation"
        uuid submission_key "idempotency key резерва"
        bigint version "optimistic locking"
    }
    TOURNAMENT_ENTRY {
        uuid id PK
        uuid tournament_id FK
        uuid user_id "логическая -> identity.app_user"
        uuid plant_id "логическая -> plants.plant"
        uuid reservation_id "логическая -> plants.plant_reservation"
        varchar status "ACTIVE | ELIMINATED | WINNER | QUEUED | QUALIFYING | FINAL_PENDING | FINALIST | WITHDRAWN, CHECK"
        timestamptz joined_at
    }
    TAG {
        uuid id PK
        varchar name UK
        timestamptz created_at
    }
    TOURNAMENT_TAG {
        uuid tournament_id PK "FK -> tournament"
        uuid tag_id PK "FK -> tag"
    }
    VOTING_WINDOW {
        uuid id PK
        uuid tournament_id FK
        varchar scope "PRIVATE | QUALIFICATION | FINAL, CHECK"
        int sequence "CHECK >= 1"
        varchar status "OPEN | CLOSED, CHECK"
        timestamptz opens_at "CHECK opens_at < closes_at, интервал [opens, closes)"
        timestamptz closes_at
        uuid epoch_id "логическая -> qualification_epoch (внутрисхемная, без FK)"
        uuid cluster_id "логическая -> geo.cluster_snapshot"
        varchar cluster_key
        timestamptz created_at
        bigint version "optimistic locking"
    }
    WINDOW_PARTICIPANT {
        uuid id PK
        uuid window_id FK
        uuid entry_id "логическая -> tournament_entry (внутрисхемная, без FK)"
        uuid user_id "логическая -> identity.app_user"
        bigint score "сумма текущих голосов, может быть отрицательной"
        varchar result "ACTIVE | SURVIVED | ELIMINATED | WINNER | PROMOTED, CHECK"
        timestamptz joined_at "tie-break: joinedAt ASC"
    }
    VOTE {
        uuid id PK
        uuid window_participant_id FK
        varchar subject_key "USER:<uuid> | GUEST:<sessionId>"
        varchar value "LIKE | DISLIKE, CHECK"
        timestamptz created_at
        timestamptz updated_at
    }
    QUALIFICATION_EPOCH {
        uuid id PK
        uuid tournament_id FK
        int sequence "CHECK >= 1"
        varchar status "OPEN | CLOSED, CHECK"
        timestamptz opens_at "CHECK opens_at < closes_at"
        timestamptz closes_at
        timestamptz created_at
        bigint version "optimistic locking"
    }
    GUEST_SESSION {
        uuid id PK
        varchar token_hash UK "sha256, токен существует только у клиента"
        timestamptz created_at "CHECK created_at < expires_at"
        timestamptz expires_at
    }
    INVITATION }o--|| TOURNAMENT : tournament_id
    TOURNAMENT_ENTRY }o--|| TOURNAMENT : tournament_id
    TOURNAMENT_TAG }o--|| TOURNAMENT : tournament_id
    TOURNAMENT_TAG }o--|| TAG : tag_id
    VOTING_WINDOW }o--|| TOURNAMENT : tournament_id
    WINDOW_PARTICIPANT }o--|| VOTING_WINDOW : window_id
    VOTE }o--|| WINDOW_PARTICIPANT : window_participant_id
    QUALIFICATION_EPOCH }o--|| TOURNAMENT : tournament_id
```

Физические связи внутри схемы (раздел 11): One-to-Many `tournament →
invitation/entry/voting_window/qualification_epoch`; Many-to-Many с
дополнительными полями `voting_window ↔ tournament_entry` через
`window_participant` (score, result, joined_at); Many-to-Many без полей
`tournament ↔ tag`.

Частичные уникальные индексы (инварианты конкурентности):

- `invitation_pair_uidx (tournament_id, user_id)` — один invite на
  пользователя/турнир (полный UNIQUE);
- `tournament_entry_pair_uidx (tournament_id, user_id) WHERE status IN
  ('ACTIVE','ELIMINATED','WINNER')` — один entry пользователя в private-турнире;
- `tournament_entry_one_active_global_uidx (user_id) WHERE tournament_id =
  <GLOBAL-id> AND status IN ('QUEUED','QUALIFYING','FINAL_PENDING','FINALIST')`
  — одно активное глобальное участие (допущение 5);
- `voting_window_private_seq_uidx / _final_seq_uidx (tournament_id, sequence)
  WHERE scope = ...` и `voting_window_qualification_cell_uidx (epoch_id,
  cluster_id) WHERE scope = 'QUALIFICATION'` — последовательности окон;
- `voting_window_one_open_private_uidx / _one_open_final_uidx (tournament_id)
  WHERE status = 'OPEN' AND scope = ...` — одно открытое окно на турнир и scope;
- `qualification_epoch_seq_uidx (tournament_id, sequence)` — полный UNIQUE,
  последовательность эпох;
- `qualification_epoch_one_open_uidx (tournament_id) WHERE status = 'OPEN'`;
- `window_participant_pair_uidx (window_id, entry_id)` — один участник на
  окно/entry; `vote_subject_uidx (window_participant_id, subject_key)` — один
  голос субъекта за участника окна (допущение 7).

Служебные индексы выборок: `tournament(status, registration_deadline)`,
`tournament(creator_id)`, `invitation(user_id, status)`,
`invitation(tournament_id, status)`, `invitation(submitted_plant_id, status)`,
`tournament_entry(tournament_id, status)`, `voting_window(status, closes_at)`,
`voting_window(scope, status, closes_at)`, `window_participant(window_id,
score DESC)`, `vote(window_participant_id)`, `guest_session(expires_at)`.

## geo

```mermaid
erDiagram
    CLUSTER_SNAPSHOT {
        uuid id PK
        uuid epoch_id "логическая -> tournaments.qualification_epoch"
        varchar cluster_key "geohash ячейки"
        varchar policy_version "фиксируется с составом"
        timestamptz created_at
    }
    CLUSTER_MEMBER {
        uuid snapshot_id PK "FK -> cluster_snapshot"
        uuid entry_id PK "логическая -> tournaments.tournament_entry"
        uuid user_id "логическая -> identity.app_user"
        bigint location_version "применяется к следующей эпохе"
    }
    CLUSTER_MEMBER }o--|| CLUSTER_SNAPSHOT : snapshot_id
```

Состав и версия политики неизменны после фиксации эпохи (снимок, не живой
кластер). Индекс `cluster_snapshot(epoch_id)` — снимки эпохи.

## feed

```mermaid
erDiagram
    FEED_CARD {
        uuid id PK
        uuid window_id "логическая -> tournaments.voting_window"
        uuid tournament_id "логическая -> tournaments.tournament"
        varchar scope "PRIVATE | QUALIFICATION | FINAL, CHECK"
        uuid cluster_id "логическая -> geo.cluster_snapshot"
        uuid entry_id "логическая -> tournaments.tournament_entry"
        uuid user_id "логическая -> identity.app_user"
        uuid plant_id "логическая -> plants.plant"
        uuid asset_id "логическая -> media.media_asset"
        varchar title "снимок plants.plant.title"
        varchar owner_display_name "снимок identity.app_user.display_name"
        timestamptz joined_at
        timestamptz closes_at
        timestamptz created_at "граница snapshotCutoff курсора"
    }
```

Read-модель (ADR-002): без агрегата, обновляется событиями
`VotingWindowOpened`/`VotingWindowClosed`, `UNIQUE(window_id, entry_id)`.
Псевдослучайный порядок — `hashtextextended(id::text, :seed)` в SQL, не колонка.
Индексы `feed_card(closes_at)` и `feed_card(created_at)` — выборка закрытий и
курсор.

## Логические межсхемные ссылки (без FK)

| Схема | Колонка | Цель (схема.таблица) |
|---|---|---|
| media | `media_asset.owner_id` | identity.app_user |
| media | `asset_claim.plant_id` | plants.plant |
| plants | `plant.owner_id`, `plant.asset_id` | identity.app_user, media.media_asset |
| plants | `image_restriction.source_entry_id` | tournaments.tournament_entry |
| plants | `plant_reservation.plant_id` | plants.plant (внутрисхемная, без FK) |
| moderation | `moderation_job.plant_id`, `.asset_id` | plants.plant, media.media_asset |
| tournaments | `tournament.creator_id`, `invitation.user_id/invited_by`, `tournament_entry.user_id`, `window_participant.user_id` | identity.app_user |
| tournaments | `invitation.submitted_plant_id/reservation_id`, `tournament_entry.plant_id/reservation_id` | plants.plant, plants.plant_reservation |
| tournaments | `voting_window.epoch_id`, `voting_window.cluster_id/cluster_key` | tournaments.qualification_epoch, geo.cluster_snapshot |
| tournaments | `window_participant.entry_id` | tournaments.tournament_entry (внутрисхемная, без FK) |
| geo | `cluster_snapshot.epoch_id` | tournaments.qualification_epoch |
| geo | `cluster_member.user_id`, `.entry_id` | identity.app_user, tournaments.tournament_entry |
| feed | `feed_card.window_id/tournament_id/entry_id`, `.plant_id`, `.asset_id`, `.user_id`, `.cluster_id` | tournaments.*, plants.plant, media.media_asset, identity.app_user, geo.cluster_snapshot |
