package com.plantarena.moderation.domain;

/** Причина завершения задания; заполняется только при DONE (раздел 6). */
public enum ModerationReasonCode {
    PLANT_DETECTED, NOT_A_PLANT, STALE
}
