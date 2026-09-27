package com.plantarena.tournaments.application.port.in;

import java.time.Instant;

/** Закрытие просроченных окон (разделы 7, 12.3): scheduler и demo-ручка. */
public interface CloseVotingWindowUseCase {

    /**
     * Закрыть due-окна (closesAt &lt;= now), пачка ≤ limit; возвращает число
     * закрытых. Идемпотентен: повтор для закрытого окна — no-op.
     */
    int closeDue(Instant now, int limit);
}
