package com.plantarena.tournaments.application;

/** Приглашение не найдено или скрыто (не адресат — раздел 13). */
public class InvitationNotFoundException extends RuntimeException {

    public InvitationNotFoundException(String message) {
        super(message);
    }
}
