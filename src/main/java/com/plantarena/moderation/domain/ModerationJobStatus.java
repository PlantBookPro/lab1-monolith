package com.plantarena.moderation.domain;

/** Статусы задания модерации: NEW → IN_PROGRESS → DONE | RETRY; RETRY → IN_PROGRESS. */
public enum ModerationJobStatus {
    NEW, IN_PROGRESS, RETRY, DONE
}
