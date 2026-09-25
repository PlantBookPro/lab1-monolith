package com.plantarena.plants.application;

import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.Plant;
import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import org.springframework.stereotype.Component;

/**
 * AccessPolicy контекста plants (раздел 2): правила единого языка контекста.
 * Видимость: своё растение — всегда; чужое — только APPROVED (черновики и
 * отклонённые не раскрываются, раздел 13); админ — служебный доступ ко всем
 * неархивированным. Изменения (rename/archive) — только владелец (раздел 13
 * не даёт админу прав на чужие растения). Скрытое — 404, не 403.
 */
@Component
public class PlantsAccessPolicy {

    public void requireIdentified(CurrentActor actor) {
        if (actor == null || actor.isGuest()) {
            throw new NotIdentifiedException(
                "Требуется идентифицированный пользователь (X-Demo-User-Id в dev/test, ADR-005)");
        }
    }

    /** Просмотр: владелец, админ или APPROVED-растение; иначе скрыто (404). */
    public void requirePlantViewer(CurrentActor actor, Plant plant) {
        requireIdentified(actor);
        if (isOwner(actor, plant) || actor.hasRole(AppRole.ADMIN)
                || plant.moderationStatus() == ModerationStatus.APPROVED) {
            return;
        }
        throw new PlantNotFoundException("Растение не найдено: " + plant.id());
    }

    /** Изменение: только владелец (вызывается после requirePlantViewer). */
    public void requirePlantOwner(CurrentActor actor, Plant plant) {
        if (isOwner(actor, plant)) {
            return;
        }
        throw new AccessDeniedException("Действие с растением доступно только владельцу");
    }

    /** Статус модерации — приватная информация владельца (раздел 13). */
    public void requireModerationViewer(CurrentActor actor, Plant plant) {
        requireIdentified(actor);
        if (isOwner(actor, plant)) {
            return;
        }
        throw new PlantNotFoundException("Растение не найдено: " + plant.id());
    }

    private boolean isOwner(CurrentActor actor, Plant plant) {
        return actor.userId() != null && actor.userId().equals(plant.ownerId());
    }
}
