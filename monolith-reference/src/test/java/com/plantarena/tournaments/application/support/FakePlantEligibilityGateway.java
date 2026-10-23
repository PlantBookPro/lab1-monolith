package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.application.ImageAlreadyReservedException;
import com.plantarena.tournaments.application.PlantNotReservableException;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Фейк порта допуска растений: записи вызовов + управляемые отказы. */
public class FakePlantEligibilityGateway implements PlantEligibilityGateway {

    public final Map<UUID, UUID> reservationsByKey = new HashMap<>();
    public final List<ConfirmCall> confirmed = new ArrayList<>();
    public final List<UUID> released = new ArrayList<>();
    public boolean conflictOnReserve;
    public boolean failOnReserve;
    public boolean failOnConfirm;

    @Override
    public UUID reserve(UUID ownerId, UUID plantId, UUID idempotencyKey) {
        if (conflictOnReserve) {
            throw new ImageAlreadyReservedException("Изображение уже зарезервировано (фейк)");
        }
        if (failOnReserve) {
            throw new PlantNotReservableException("Растение не проходит проверки (фейк)", null);
        }
        UUID reservationId = UUID.randomUUID();
        reservationsByKey.put(idempotencyKey, reservationId);
        return reservationId;
    }

    @Override
    public void confirm(UUID ownerId, UUID plantId, UUID reservationId) {
        if (failOnConfirm) {
            throw new PlantNotReservableException("Резерв недействителен (фейк)", null);
        }
        confirmed.add(new ConfirmCall(ownerId, plantId, reservationId));
    }

    @Override
    public void release(UUID reservationId) {
        released.add(reservationId);
    }

    public boolean isReleased(UUID reservationId) {
        return released.contains(reservationId);
    }

    public record ConfirmCall(UUID ownerId, UUID plantId, UUID reservationId) {
    }
}
