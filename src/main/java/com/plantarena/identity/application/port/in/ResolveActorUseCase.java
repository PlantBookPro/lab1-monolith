package com.plantarena.identity.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/**
 * Идентификация субъекта по userId: демо-заголовок ADR-005 (лаба №1),
 * Spring Security + JWT (лаба №3) — меняется только адаптер.
 */
public interface ResolveActorUseCase {

    CurrentActor resolve(UUID userId);
}
