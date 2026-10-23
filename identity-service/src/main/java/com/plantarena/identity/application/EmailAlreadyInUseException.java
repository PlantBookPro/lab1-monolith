package com.plantarena.identity.application;

import com.plantarena.identity.domain.Email;


public class EmailAlreadyInUseException extends RuntimeException {

    public EmailAlreadyInUseException(Email email) {
        super("Email уже используется: " + email.value());
    }
}
