package com.plantarena.support;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Singleton Testcontainers-контейнер PostgreSQL на весь прогон тестов данной JVM.
 */
public final class PostgresSupport {

    private static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer("postgres:17.5-alpine")
            .withDatabaseName("plantarena")
            .withUsername("postgres")
            .withPassword("postgres");

    static {
        POSTGRES.start();
    }

    private PostgresSupport() {
    }

    public static PostgreSQLContainer postgres() {
        return POSTGRES;
    }
}
