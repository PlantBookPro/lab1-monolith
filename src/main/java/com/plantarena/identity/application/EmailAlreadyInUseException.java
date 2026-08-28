package com.plantarena.identity.application;

import com.plantarena.identity.domain.Email;

/**
 * Email уже используется другой учётной записью (переводится в HTTP 409 адаптером).
 */
public class EmailAlreadyInUseException extends RuntimeException {

    public EmailAlreadyInUseException(Email email) {
        super("Email уже используется: " + email.value());
    }
}
