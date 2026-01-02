package com.plantarena.tournaments.domain;

/**
 * Переходы заявки (раздел 7): INVITED → ACCEPTED_PENDING_MODERATION → READY;
 * INVITED → DECLINED/REVOKED/EXPIRED; ACCEPTED_PENDING_MODERATION →
 * INVITED (REJECTED, повторная подача) / DECLINED / EXPIRED. REVOKED/EXPIRED
 * и DECLINED терминальны; поздний результат модерации их не меняет.
 */
public enum InvitationStatus {
    INVITED, ACCEPTED_PENDING_MODERATION, READY, DECLINED, REVOKED, EXPIRED
}
