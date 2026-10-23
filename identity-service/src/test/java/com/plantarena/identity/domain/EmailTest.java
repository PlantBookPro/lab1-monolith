package com.plantarena.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VO Email: нормализация и формат")
class EmailTest {

    @Test
    void email_нормализуется_к_нижнему_регистру_и_без_пробелов() {
        assertThat(new Email("  Alice@Example.COM ").value()).isEqualTo("alice@example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "no-at-sign", "a@b", "a b@example.com", "a@@example.com"})
    void некорректный_email_невозможно_создать(String value) {
        assertThatThrownBy(() -> new Email(value))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
