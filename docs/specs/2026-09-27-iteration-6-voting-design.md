# Дизайн итерации 6: голосование и закрытие раундов

Дата: 2026-09-27. Статус: предложен. Источник: `docs/requirements.md`
(разделы 7, 9, 11, 12.1, 12.3, 13), `docs/plans/2026-09-23-roadmap.md` (итерация 6).

## Решения

1. **Один агрегат `VotingWindow`** (разделы 5, 12.1): корень + `WindowParticipant`
   (счёт, итог) + `Vote`. Голосование и закрытие окна сериализуются
   `SELECT ... FOR UPDATE` по строке окна (порт
   `VotingWindowRepository.findByIdForUpdate`); порядок блокировок —
   окно → window_participant → vote. Проверка времени — после получения
   блокировки. Ограничение throughput (одна tx на окно) признаётся явно.
2. **`VotingSubject` — только USER в итерации 6.** Гостевые сессии и голоса
   GUEST — итерация 8 (гость имеет право голоса только в глобальных окнах,
   которые появляются в итерации 7). `subjectKey` — строка `USER:<uuid>`;
   GUEST добавит `GUEST:<hash>` без смены схемы (VARCHAR).
3. **Дельты — чистая доменная функция**
   `VoteValue.transitionDelta(previous, next)` (null = голоса не было):
   новый LIKE +1, новый DISLIKE −1, LIKE→DISLIKE −2, DISLIKE→LIKE +2,
   повтор того же значения 0; удаление — компенсация вклада, повторное
   удаление безопасно (no-op). Score у `WindowParticipant`, BIGINT, может
   быть отрицательным; инвариант «score = сумма текущих голосов окна и
   entry» защищается блокировкой окна и проверяется конкурентным тестом.
4. **Окно создаётся при старте (sequence 1) и при закрытии предыдущего**
   (sequence + 1, выжившие, счёт с нуля). `joinedAt` участника окна берётся
   из `TournamentEntry.joinedAt` (стабилен между раундами — детерминированный
   tie-break). Интервал полуоткрытый `[opensAt, closesAt)`: в `closesAt`
   новый голос уже запрещён. Частичный уникальный индекс — не более одного
   OPEN-окна на турнир.
5. **Закрытие — один use case `CloseVotingWindowUseCase.closeDue(now, limit)`**
   для scheduler'а (`adapter.in.jobs`, fixedDelay 2с) и demo-ручки
   `POST /internal/demo/jobs/run-due` (расширяется). Идемпотентность — по
   статусу: повторный close для CLOSED-окна — no-op, ничего не начисляет и
   не убивает повторно (раздел 12.3).
6. **Выбывание:** стратегия `EliminationAlgorithm`, реализация
   `RoundElimination`: `min(n − 1, max(1, floor(n · eliminationFraction)))`.
   Рейтинг `ParticipantRanking`: score DESC, joinedAt ASC, entryId ASC
   (допущение 8; при отсутствии голосов — тот же порядок). Если после
   выбывания остаётся один — он WINNER, турнир FINISHED, окно с одним
   участником не создаётся (раздел 7). PROMOTED (глобальная квалификация)
   появится в итерации 7 расширением CHECK.
7. **Межконтекстная tx закрытия — ADR-011** («работает только в монолите»,
   план saga лабы №2): окно + итоговый рейтинг + `TournamentEntry`
   (ELIMINATED/WINNER) + `Tournament.finish` + команды plants
   (`PlantLifecycle.registerDeath` PERMANENT, `PlantEligibility.release`) в
   одной транзакции. Растения обрабатываются в устойчивом порядке по
   `plantId` (раздел 12.3). События `EntryEliminated` (на каждого выбывшего)
   и `TournamentFinished` публикуются в той же tx.
8. **Права голоса (private):** только участник, допущенный к старту (есть
   `TournamentEntry`, включая ELIMINATED — допущение 9); организатор без
   участия — 403; не имеющий доступа к турниру — 404 (скрыто, как просмотр);
   гость — 401 (до итерации 8); самоголосование — 403 (допущение 6);
   закрытое/истёкшее окно — 409; entry не из окна — 404; неизвестное значение
   голоса — 400.
9. **REST (раздел 13):** `PUT/DELETE /windows/{windowId}/entries/{entryId}/vote`,
   `GET .../my-vote`; `GET /tournaments/{id}/rounds`, `/{id}/leaderboard`
   (без `windowId` — текущее/последнее окно), `/{id}/results`. Page-списки с
   `X-Total-Count`; PUT возвращает 200 с новым score, DELETE — 204,
   повторный DELETE безопасен.
10. **Первое окно — расширение `StartTournamentService.doStart`** (тот же use
    case, что итерация 5): после `TournamentEntry.admit` создаётся
    `VotingWindow.open(..., sequence = 1, ...)` из допущенных entries.

## Архитектура и поток

```
старт (итерация 5 + окно): RUNNING + TournamentEntry(ACTIVE)
    + VotingWindow #1 (OPEN, [now, now + roundDuration), score 0)
PUT /windows/{w}/entries/{e}/vote (участник турнира, не сам, окно открыто):
    FOR UPDATE окно → проверки → vote + дельта score (одна tx, один агрегат)
дедлайн окна (scheduler / demo-ручка — closeDue, один use case):
    FOR UPDATE окно → повтор для CLOSED — no-op
    → рейтинг (score DESC, joinedAt ASC, entryId ASC)
    → eliminatedCount = min(n−1, max(1, floor(n·f))) худших
    → выжившие ≥ 2: SURVIVED + следующее окно (sequence+1, счёт с нуля)
    → выживший = 1: WINNER, Tournament FINISHED, резерв победителя освобождён
    → выбывшие (по plantId по возрастанию): entry ELIMINATED,
      PlantLifecycle.registerDeath(PERMANENT), PlantEligibility.release,
      EntryEliminated                                    ── одна tx (ADR-011)
```

## Домен

- `VotingWindow`: `open` (фабрика, ≥ 2 участника), `castVote`/`removeVote`
  (дельты, самоголосование запрещено на уровне агрегата — userId
  денормализован в `WindowParticipant`), `close(now, algorithm, fraction)`
  → `CloseOutcome(eliminatedEntryIds, survivedEntryIds, winnerEntryId)`.
- `WindowParticipant`: score/result; изменяется только агрегатом.
- `Vote`: value LIKE/DISLIKE, createdAt/updatedAt.
- `VoteValue.transitionDelta` — чистая функция дельт.
- `EliminationAlgorithm`/`RoundElimination`, `ParticipantRanking` — чистые
  доменные сервисы (раздел 5).
- `Tournament.finish(now)`: RUNNING → FINISHED. `TournamentEntry`:
  `eliminate()`/`declareWinner()` (ACTIVE → ELIMINATED/WINNER).

## Хранилище (схема `tournaments`, миграция V3)

`voting_window` (UNIQUE(tournament_id, sequence), частичный UNIQUE
(tournament_id) WHERE status = 'OPEN', индекс status+closes_at для
scheduler'а, version), `window_participant` (UNIQUE(window_id, entry_id),
score BIGINT, индекс window_id+score DESC), `vote` (FK →
window_participant, subject_key, UNIQUE(window_participant_id,
subject_key)). Enum → VARCHAR + CHECK (PROMOTED — итерация 7). Маппинг
явный; блокировка — `@Lock(PESSIMISTIC_WRITE)`.

## Тестирование

Пирамида раздела 14: доменные unit (дельты — параметризованные, инварианты
окна, RoundElimination на границах n, tie-break) → application (in-memory
фейки: права, закрытие, идемпотентность, первый раунд при старте) →
контрактные тесты репозитория (fake + JPA/Testcontainers) → приёмочный
`VotingApiIT` (полный цикл до победителя через HTTP, детерминированный
классификатор, короткие раунды + Awaitility) → конкурентный
`VotingConcurrencyIT` (параллельные голоса, гонка голоса и закрытия,
повторное закрытие; инварианты БД, а не отсутствие исключений). DoD
итерации: разделы 9 (голоса) и 12.1/12.3 покрыты, включая гонки; зелёный
`./mvnw verify`.
