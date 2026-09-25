package com.plantarena.plants.api;

import java.util.UUID;

/**
 * Опубликованный контракт plants для tournaments (раздел 6): две операции
 * допуска. reserveSubmission допускает статус PENDING/APPROVED (незавершённая
 * модерация разрешает подачу заявки, но не допуск к голосованию);
 * confirmEligibility допускает к старту только APPROVED и подтверждает
 * существующий резерв. Проверки и изменение резерва атомарны внутри plants.
 */
public interface PlantEligibility {

    /**
     * Зарезервировать изображение за заявкой. Проверяет владельца, ALIVE,
     * отсутствие запрета и допустимый статус; идемпотентен по ключу:
     * повтор с тем же ключом возвращает тот же reservationId.
     *
     * @throws PlantNotEligibleException растение не проходит проверки
     * @throws ReservationConflictException изображение уже активно зарезервировано
     */
    UUID reserveSubmission(UUID ownerId, UUID plantId, UUID idempotencyKey);

    /**
     * Подтвердить допуск к старту: только APPROVED и активный резерв
     * этой же заявки.
     *
     * @throws PlantNotEligibleException растение/резерв не проходят проверки
     */
    void confirmEligibility(UUID ownerId, UUID plantId, UUID reservationId);

    /**
     * Освободить резерв (отказ/отмена/завершение). Идемпотентно.
     */
    void releaseReservation(UUID reservationId);
}
