package com.plantarena.plants.domain;

/** Вид запрета изображения (раздел 6): PERMANENT без expiresAt; COOLDOWN активен при now &lt; expiresAt. */
public enum RestrictionKind {
    PERMANENT, COOLDOWN
}
