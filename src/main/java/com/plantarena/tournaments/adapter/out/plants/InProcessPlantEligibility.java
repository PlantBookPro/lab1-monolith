package com.plantarena.tournaments.adapter.out.plants;

import com.plantarena.plants.api.PlantEligibility;
import com.plantarena.plants.api.PlantNotEligibleException;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.api.ReservationConflictException;
import com.plantarena.tournaments.application.ImageAlreadyReservedException;
import com.plantarena.tournaments.application.InvitedPlantNotFoundException;
import com.plantarena.tournaments.application.PlantNotReservableException;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ACL-адаптер plants → tournaments (раздел 4.3, Consumer-driven): команды
 * допуска plants.api.PlantEligibility; исключения plants переводятся в
 * единый язык tournaments (retryAt — срок временного запрета изображения,
 * у остальных проверок — null). В лабе №2 меняется на HTTP-клиент
 * plant-service с той же трансляцией ошибок.
 */
@Component
public class InProcessPlantEligibility implements PlantEligibilityGateway {

    private final PlantEligibility plantEligibility;

    public InProcessPlantEligibility(PlantEligibility plantEligibility) {
        this.plantEligibility = plantEligibility;
    }

    @Override
    public UUID reserve(UUID ownerId, UUID plantId, UUID idempotencyKey) {
        try {
            return plantEligibility.reserveSubmission(ownerId, plantId, idempotencyKey);
        } catch (PlantNotEligibleException e) {
            throw new PlantNotReservableException(e.getMessage(), e.restrictedUntil());
        } catch (ReservationConflictException e) {
            throw new ImageAlreadyReservedException(e.getMessage());
        } catch (PlantNotFoundException e) {
            throw new InvitedPlantNotFoundException(e.getMessage());
        }
    }

    @Override
    public void confirm(UUID ownerId, UUID plantId, UUID reservationId) {
        try {
            plantEligibility.confirmEligibility(ownerId, plantId, reservationId);
        } catch (PlantNotEligibleException e) {
            throw new PlantNotReservableException(e.getMessage(), e.restrictedUntil());
        } catch (PlantNotFoundException e) {
            throw new InvitedPlantNotFoundException(e.getMessage());
        }
    }

    @Override
    public void release(UUID reservationId) {
        plantEligibility.releaseReservation(reservationId);
    }
}
