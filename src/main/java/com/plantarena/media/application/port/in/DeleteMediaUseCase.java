package com.plantarena.media.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/**
 * Удаление файла (раздел 13): владелец или админ; только незадействованный
 * файл — проверка задействованности появится с plants (итерация 3).
 */
public interface DeleteMediaUseCase {

    void delete(CurrentActor actor, UUID assetId);
}
