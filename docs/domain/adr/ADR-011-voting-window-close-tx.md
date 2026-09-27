# ADR-011: Закрытие окна голосования — межконтекстная транзакция и идемпотентность

Дата: 2026-09-27. Статус: принято (итерация 6). Контекст: tournaments → plants
(разделы 12.3, 4.3).

## Решение

Закрытие окна — один межконтекстный процесс в одной транзакции монолита
(`CloseVotingWindowService.closeOne`, TransactionTemplate):

1. `findByIdForUpdate` — `SELECT ... FOR UPDATE` строки `voting_window`
   (та же блокировка, что у голосования — раздел 12.1). Повтор для CLOSED
   или ещё не due — no-op (идемпотентность повтора по `(windowId, sequence)`
   на уровне use case по статусу).
2. Закрытие доменом: рейтинг `ParticipantRanking` (score DESC, joinedAt ASC,
   entryId ASC), выбывание `RoundElimination`
   (`min(n − 1, max(1, floor(n · eliminationFraction)))` худших), результаты
   участников (`WindowParticipant.eliminate/survive/declareWinner`).
3. `TournamentEntry.eliminate/declareWinner` + `Tournament.finish`
   (мутация entry сохраняется `TournamentEntryRepository.save` — статус
   ELIMINATED/WINNER переживает перезагрузку).
4. Команды plants для выбывших в устойчивом порядке по `plantId`:
   `PlantLifecycle.registerDeath(PERMANENT, sourceEntryId)` (гибель + запрет,
   идемпотентна на стороне plants) и `PlantEligibility.release` (резерв).
5. Победитель: освобождение резерва, `TournamentFinished`; иначе — следующий
   `VotingWindow` (sequence + 1, выжившие, счёт с нуля).
6. События `EntryEliminated`/`TournamentFinished` публикуются в той же tx.

Каждое окно — отдельная короткая tx: `closeDue` берёт due-окна из БД и
закрывает каждое в своей tx (как старт турнира — ADR-010); один сбой не
блокирует остальные (повтор — следующий poll). `now` — аргумент use case:
единственный источник времени и для due-выбора, и для решения о закрытии
(один момент времени на весь процесс).

Голосование — одна tx, один агрегат `VotingWindow` (раздел 12.1), порядок
блокировок окно → window_participant → vote; проверка времени после
блокировки. Ограничение throughput (сериализация голосов одного окна)
признаётся: учебный масштаб.

## Почему

Атомарность «итог окна + гибель + запрет + резервы + следующий раунд» —
обязательное последствие (раздел 10.3), а не побочный эффект: частичное
применение оставило бы выбывшее растение живым или живой резерв у погибшего.

## Только в монолите

В лабе №2 процесс становится saga: локально фиксируются окно и итоги
(ELIMINATED немедленно исключает из голосования/ленты), гибель доставляется
надёжной командой с повтором (идемпотентность — DEAD-статус plants),
резерв держится до подтверждения гибели, компенсация — release orphan-резервов.
В лабе №4 — outbox/inbox (`tournament.lifecycle.v1`, `plant.lifecycle.v1`).
