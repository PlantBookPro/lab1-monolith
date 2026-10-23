package com.plantarena.moderation.application.support;

import com.plantarena.moderation.application.DecisionConflictException;
import com.plantarena.moderation.application.port.out.PlantModerationGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Фейк команды решения: помнит вызовы; конфликт включается флагом. */
public class FakePlantModerationGateway implements PlantModerationGateway {

    public final List<Recorded> decisions = new ArrayList<>();
    public DecisionConflictException conflict;

    @Override
    public void recordDecision(UUID plantId, Decision decision, String reason) {
        if (conflict != null) {
            throw conflict;
        }
        decisions.add(new Recorded(plantId, decision, reason));
    }

    public record Recorded(UUID plantId, Decision decision, String reason) {
    }
}
