package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import java.util.List;
import java.util.UUID;

/**
 * Список доступных пользователю турниров с фильтрами (раздел 13). Статус —
 * строка опубликованного языка (как TournamentData.status): адаптер in.web
 * не зависит от домена (LayerRules), разбор значения — в application.
 */
public interface ListTournamentsUseCase {

    /**
     * @param status фильтр статуса (DRAFT/REGISTRATION_OPEN/RUNNING/
     *               FINISHED/CANCELLED) или null — все статусы
     * @throws com.plantarena.tournaments.application.UnknownStatusFilterException
     *         неизвестное значение фильтра (раздел 13: 400)
     */
    TournamentListResult list(CurrentActor actor, String status, UUID tagId,
                              int page, int size);

    record TournamentListResult(List<TournamentData> items, long total) {
    }
}
