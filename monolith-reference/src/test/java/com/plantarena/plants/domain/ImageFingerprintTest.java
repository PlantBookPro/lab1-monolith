package com.plantarena.plants.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VO ImageFingerprint (plants): 64 hex + версия алгоритма")
class ImageFingerprintTest {

    private static final String VALID = "a".repeat(64);

    @Test
    void корректный_отпечаток_создаётся() {
        ImageFingerprint fingerprint = new ImageFingerprint(VALID, 1);

        assertThat(fingerprint.value()).isEqualTo(VALID);
        assertThat(fingerprint.algorithmVersion()).isEqualTo(1);
    }

    @Test
    void некорректное_значение_невозможно_создать() {
        assertThatThrownBy(() -> new ImageFingerprint(null, 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageFingerprint("ABC", 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageFingerprint("Z".repeat(64), 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageFingerprint("a".repeat(63), 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void версия_алгоритма_положительна() {
        assertThatThrownBy(() -> new ImageFingerprint(VALID, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
