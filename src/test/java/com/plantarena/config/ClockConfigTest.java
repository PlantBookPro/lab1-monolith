package com.plantarena.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Часы приложения всегда в UTC")
class ClockConfigTest {

    @Test
    void clock_бин_использует_UTC() {
        Clock clock = new ClockConfig().utcClock();

        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
