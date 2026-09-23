package com.plantarena;

import com.plantarena.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Приложение запускается на реальном PostgreSQL (Testcontainers)")
class ApplicationStartupIT extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void контекст_приложения_поднимается() {
        assertThat(applicationContext).isNotNull();
    }
}
