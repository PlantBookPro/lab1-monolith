package com.plantarena.tournaments.application.port.out;

import java.util.UUID;

/**
 * Выходной порт tournaments: допуск растения (раздел 6, Consumer-driven).
 * Адаптер tournaments.adapter.out.plants вызывает plants.api.PlantEligibility
 * и переводит его исключения в исключения tournaments (ACL, раздел 4.3).
 */
public interface PlantEligibilityGateway {

    /**
     * Зарезервировать изображение за заявкой (fresh idempotency key на каждую
     * попытку принятия приглашения — дизайн итерации 5, решение 3).
     *
     * @throws com.plantarena.tournaments.application.PlantNotReservableException
     *         растение не проходит проверки plants (не владелец/погибло/запрет/не APPROVED)
     * @throws com.plantarena.tournaments.application.ImageAlreadyReservedException
     *         изображение уже активно зарезервировано другой заявкой
     */
    UUID reserve(UUID ownerId, UUID plantId, UUID idempotencyKey);

    /**
     * Подтвердить допуск к старту: только APPROVED и действующий резерв этой заявки.
     *
     * @throws com.plantarena.tournaments.application.PlantNotReservableException проверки не пройдены
     */
    void confirm(UUID ownerId, UUID plantId, UUID reservationId);

    /** Освободить резерв (отказ/отмена/EXPIRED). Идемпотентно. */
    void release(UUID reservationId);
}
