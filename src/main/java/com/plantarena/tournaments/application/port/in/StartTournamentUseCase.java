package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import java.time.Instant;
import java.util.UUID;

/**
 * Один use case старта для ручки и scheduler'а (раздел 7): start — ручка
 * организатора (только после дедлайна), startDue — обработка наступивших
 * дедлайнов (poller и demo-ручка). Идемпотентен по статусу турнира.
 */
public interface StartTournamentUseCase {

    TournamentData start(CurrentActor actor, UUID tournamentId);

    /** Обработать due-турниры; возвращает число запущенных/отменённых. */
    int startDue(Instant now, int limit);
}
