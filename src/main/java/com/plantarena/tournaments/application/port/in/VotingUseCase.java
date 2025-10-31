package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.Optional;
import java.util.UUID;

/** Голосование в окне (раздел 9): PUT/DELETE/my-vote. */
public interface VotingUseCase {

    /** Установить голос (LIKE/DISLIKE); возвращает новый счёт участника. */
    long cast(CurrentActor actor, UUID windowId, UUID entryId, String value);

    /** Удалить свой голос (идемпотентно); возвращает счёт после удаления. */
    long remove(CurrentActor actor, UUID windowId, UUID entryId);

    /** Текущий голос субъекта за участника (пусто — голоса нет). */
    Optional<String> myVote(CurrentActor actor, UUID windowId, UUID entryId);
}
