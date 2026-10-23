package com.plantarena.tournaments.adapter.out.plants;

import com.plantarena.shared.web.ApiError;
import com.plantarena.tournaments.api.client.ApiErrorDecoder;
import com.plantarena.tournaments.api.client.PlantsDirectoryClient;
import com.plantarena.tournaments.application.ImageAlreadyReservedException;
import com.plantarena.tournaments.application.InvitedPlantNotFoundException;
import com.plantarena.tournaments.application.PlantNotReservableException;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import feign.FeignException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HttpPlantEligibility implements PlantEligibilityGateway {

    private final PlantsDirectoryClient plantsClient;

    @Override
    public UUID reserve(UUID ownerId, UUID plantId, UUID idempotencyKey) {
        try {
            return plantsClient.reserve(new PlantsDirectoryClient.ReserveRequest(ownerId, plantId,
                idempotencyKey)).reservationId();
        } catch (FeignException e) {
            throw translate(e);
        }
    }

    @Override
    public void confirm(UUID ownerId, UUID plantId, UUID reservationId) {
        try {
            plantsClient.confirm(reservationId, new PlantsDirectoryClient.ConfirmRequest(ownerId, plantId));
        } catch (FeignException e) {
            throw translate(e);
        }
    }

    @Override
    public void release(UUID reservationId) {
        plantsClient.release(reservationId);
    }

    private RuntimeException translate(FeignException e) {
        ApiError error = ApiErrorDecoder.decode(e).orElseThrow(() -> e);
        return switch (error.code()) {
            case "PLANT_NOT_ELIGIBLE" -> new PlantNotReservableException(error.detail(), error.retryAt());
            case "RESERVATION_CONFLICT" -> new ImageAlreadyReservedException(error.detail());
            case "PLANT_NOT_FOUND" -> new InvitedPlantNotFoundException(error.detail());
            default -> e;
        };
    }
}
