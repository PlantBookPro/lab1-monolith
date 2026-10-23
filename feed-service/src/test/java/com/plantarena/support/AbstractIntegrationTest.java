package com.plantarena.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Базовый класс всех IT feed-service (WebFlux): Testcontainers PostgreSQL
 * (singleton), профиль test, WebTestClient на RANDOM_PORT (без MockMvc),
 * заголовочная идентификация-дубль (TestHeaderReactiveCurrentActorProvider,
 * ADR-005 — без identity-БД). Eureka и config-server в IT отключены.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.cloud.config.enabled", () -> "false");
    }
}
