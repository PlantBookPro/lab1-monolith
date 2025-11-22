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
            .withPassword("postgres")
            // один контейнер на JVM для всех IT: ~15 кэшированных тест-контекстов
            // (полные @SpringBootTest + @DataJpaTest-слайсы) держат пулы Hikari
            // открытыми; дефолтных max_connections=100 не хватает («too many
            // clients already»)
            .withCommand("postgres", "-c", "max_connections=400");

    static {
        POSTGRES.start();
    }

    private PostgresSupport() {
    }

    public static PostgreSQLContainer postgres() {
        return POSTGRES;
    }
}
