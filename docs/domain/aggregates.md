# Реестр агрегатов и инвариантов

Источник: `docs/requirements.md`, раздел 5. Колонка «защищающий тест»
заполняется итерациями 1–9 реальными именами тестов; на итерации 0 — каркас.

| Контекст | Агрегат (корень) | Состав | Ключевые инварианты | Команды | Доменные события | Защищающий тест |
|---|---|---|---|---|---|---|
| identity | `User` | роли, профиль, координаты, хэш пароля, статус | USER всегда присутствует; роли меняются только отдельными командами; координаты в допустимых диапазонах | создать USER, изменить профиль, назначить/снять роль, деактивировать | — | (итерация 1) |
| media | `MediaAsset` | метаданные файла | Неизменяем после создания | загрузить файл | — | (итерация 2) |
| plants | `Plant` | ownerId, assetId, fingerprint, статусы модерации и жизни | Переходы модерации и жизни; DEAD необратим; asset не меняется после подачи | подать заявку, применить решение модерации, погибнуть | `PlantSubmitted`, `PlantModerationDecided`, `PlantDied` | (итерация 3) |
| plants | `ImageRestriction` | ownerId, fingerprint, kind, expiresAt, reason, sourceEntryId | PERMANENT без expiresAt; COOLDOWN активен при `now < expiresAt` | наложить запрет | — | (итерация 3) |
| plants | `PlantReservation` | ownerId, fingerprint, idempotency key, статус | Не более одного активного резерва на (ownerId, fingerprint) — set-инвариант, частичный UNIQUE-индекс PostgreSQL | зарезервировать, освободить | — | (итерация 3) |
| moderation | `ModerationJob` | plantId, assetId, status, attempts, nextAttemptAt, результат | Ошибка распознавателя не является решением; устаревший результат не применяется | создать задание, выполнить попытку, применить результат | — | (итерация 4) |
| tournaments | `Tournament` | параметры, type, status, version | State machine DRAFT → REGISTRATION_OPEN → RUNNING → FINISHED / CANCELLED; параметры и их допустимые изменения | создать черновик, открыть регистрацию, стартовать, отменить | `TournamentStarted`, `TournamentFinished` | (итерация 5) |
| tournaments | `Invitation` | tournamentId, userId, статус, submittedPlantId, reservationId | Переходы статусов, дедлайн, уникальность (tournamentId, userId) | пригласить, отозвать, принять, отклонить | `InvitationCreated` | (итерация 5) |
| tournaments | `TournamentEntry` | tournamentId, userId, plantId, reservationId, статус | State machine для PRIVATE (ACTIVE → ELIMINATED/WINNER) и GLOBAL (QUEUED → QUALIFYING → FINAL_PENDING → FINALIST → ELIMINATED, WITHDRAWN из QUEUED) | допустить к старту, выбыть, победить, сняться | `EntryEliminated` | (итерации 5–7) |
| tournaments | `VotingWindow` (+ `WindowParticipant`, `Vote`) | состав, счёт, голоса | `[opensAt, closesAt)`; состав зафиксирован; score = сумма текущих голосов; повторное закрытие не меняет результатов | проголосовать, изменить голос, удалить голос, закрыть окно | — | (итерация 6) |
| tournaments | `GlobalCompetition`, `QualificationEpoch` | эпохи, составы | GLOBAL никогда не FINISHED; порядок обработки одновременных границ | открыть эпоху, закрыть квалификацию, закрыть финал | — | (итерация 7) |
| tournaments | `GuestSession` | хэш токена, срок действия, лимиты | Хранится только хэш токена; срок действия | создать сессию | — | (итерация 8) |
| geo | `ClusterSnapshot` | epochId, clusterKey, policyVersion, состав | Состав и версия политики неизменны после фиксации эпохи | зафиксировать состав эпохи | — | (итерация 7) |

## Доменные сервисы и политики (чистый Java)

- `EliminationAlgorithm` (стратегия; реализация `RoundElimination`) — итерация 6
- `ParticipantRanking` (tie-break: score DESC, joinedAt ASC, entryId ASC) — итерация 6
- `ClusteringPolicy` (geohash) — итерация 7
- `ImageReusePolicy` — итерация 3
- `FeedOrdering` (псевдослучайный ключ от server-issued seed) — итерация 8
