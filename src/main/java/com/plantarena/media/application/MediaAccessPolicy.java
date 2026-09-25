package com.plantarena.media.application;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Права доступа media (раздел 13): загрузка — любой идентифицированный
 * пользователь; скачивание — только владелец (чужие файлы скрыты, 404);
 * удаление — владелец или админ (чужим — 403).
 */
@Component
public class MediaAccessPolicy {

    public void requireUploader(CurrentActor actor) {
        if (actor.isGuest()) {
            throw new NotIdentifiedException(
                "Загрузка файлов доступна только идентифицированным пользователям");
        }
    }

    public void requireViewer(CurrentActor actor, UUID ownerId) {
        if (actor.isGuest()) {
            throw new NotIdentifiedException(
                "Скачивание файлов доступно только идентифицированным пользователям");
        }
        if (!actor.userId().equals(ownerId)) {
            throw new MediaAssetNotFoundException("Файл не найден"); // скрыт приватностью
        }
    }

    public void requireDeleter(CurrentActor actor, UUID ownerId) {
        if (actor.isGuest()) {
            throw new NotIdentifiedException(
                "Удаление файлов доступно только идентифицированным пользователям");
        }
        if (!actor.userId().equals(ownerId) && !actor.hasRole(AppRole.ADMIN)) {
            throw new AccessDeniedException("Удалить файл может владелец или администратор");
        }
    }
}
