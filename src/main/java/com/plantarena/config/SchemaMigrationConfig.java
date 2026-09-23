package com.plantarena.config;

import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Миграции Flyway по контекстам (ADR-003): у каждого контекста свой каталог
 * db/migration/&lt;context&gt; и своя flyway_schema_history в своей схеме.
 * Выполняется при создании бина: пользовательские @Bean-методы регистрируются
 * и создаются раньше авто-конфигураций (в т.ч. EntityManagerFactory),
 * поэтому Hibernate validate видит уже мигрированные схемы.
 */
@Configuration
public class SchemaMigrationConfig {

    public static final List<String> CONTEXT_SCHEMAS =
        List.of("identity", "media", "plants", "moderation", "tournaments", "geo", "feed");

    @Bean
    public SchemaMigrations schemaMigrations(DataSource dataSource) {
        SchemaMigrations migrations = new SchemaMigrations(dataSource);
        migrations.migrateAll();
        return migrations;
    }

    public static final class SchemaMigrations {

        private final DataSource dataSource;

        private SchemaMigrations(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        void migrateAll() {
            for (String context : CONTEXT_SCHEMAS) {
                Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration/" + context)
                    .schemas(context)
                    .defaultSchema(context)
                    .table("flyway_schema_history")
                    .load()
                    .migrate();
            }
        }
    }
}
