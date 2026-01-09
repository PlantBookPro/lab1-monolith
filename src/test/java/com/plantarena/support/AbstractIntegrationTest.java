package com.plantarena.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Базовый класс всех IT: Testcontainers PostgreSQL (singleton), профиль test
 * (демо-идентификация ADR-005) и bootstrap-админ из ENV (раздел 2 требований).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("plantarena.bootstrap-admin.email", () -> "admin@plantarena.local");
        registry.add("plantarena.bootstrap-admin.password", () -> "admin-password-123");
        registry.add("plantarena.bootstrap-admin.display-name", () -> "Bootstrap Admin");
    }
}
