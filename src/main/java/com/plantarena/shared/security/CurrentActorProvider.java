package com.plantarena.shared.security;

/**
 * Провайдер текущего субъекта. Реализует контекст identity:
 * в лабе №1 — демо-заголовок X-Demo-User-Id (ADR-005),
 * в лабе №3 заменяется на Spring Security + JWT без изменения потребителей.
 */
public interface CurrentActorProvider {

    CurrentActor currentActor();
}
