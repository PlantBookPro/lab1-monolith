package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.Optional;
import java.util.UUID;

/**
 * Голосование (раздел 9): субъект — USER всегда для идентифицированного
 * (гостевой токен игнорируется) или GUEST по X-Guest-Token (только
 * глобальные окна). my-vote — те же правила.
 */
public interface VotingUseCase {

    /** Установить голос (LIKE/DISLIKE); возвращает новый счёт участника. */
    long cast(CurrentActor actor, String guestToken, UUID windowId, UUID entryId, String value);

    /** Удалить свой голос (идемпотентно); возвращает счёт после удаления. */
    long remove(CurrentActor actor, String guestToken, UUID windowId, UUID entryId);

    /** Текущий голос субъекта за участника (пусто — голоса нет). */
    Optional<String> myVote(CurrentActor actor, String guestToken, UUID windowId, UUID entryId);
}
