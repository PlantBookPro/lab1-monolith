package com.plantarena.config;

import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Миграции Flyway по контекстам (ADR-003): у каждого контекста свой каталог
 * db/migration/&lt;context&gt; и своя flyway_schema_history в своей схеме.
 * Миграции выполняются при инициализации бина schemaMigrations (PostConstruct);
 * статический BeanFactoryPostProcessor (аналог Boot FlywayJpaDependencyConfigurer)
 * заставляет entityManagerFactory создаваться ПОСЛЕ миграций — в т.ч. в test-slice
 * (@DataJpaTest), где порядок создания бинов отличается от полного приложения,
 * поэтому Hibernate validate всегда видит уже мигрированные схемы.
 */
@Configuration
public class SchemaMigrationConfig {

    public static final List<String> CONTEXT_SCHEMAS =
        List.of("identity", "media", "plants", "moderation", "tournaments", "geo", "feed");

    @Bean
    public SchemaMigrations schemaMigrations(DataSource dataSource) {
        return new SchemaMigrations(dataSource);
    }

    @Bean
    static BeanFactoryPostProcessor entityManagerFactoryDependsOnMigrations() {
        return beanFactory -> {
            for (String dependent : new String[] { "entityManagerFactory", "jpaMappingContext" }) {
                if (beanFactory.containsBeanDefinition(dependent)
                    && beanFactory.containsBeanDefinition("schemaMigrations")) {
                    BeanDefinition definition = beanFactory.getBeanDefinition(dependent);
                    definition.setDependsOn(merge(definition.getDependsOn(), "schemaMigrations"));
                }
            }
        };
    }

    private static String[] merge(String[] dependsOn, String addition) {
        if (dependsOn == null || dependsOn.length == 0) {
            return new String[] { addition };
        }
        String[] merged = Arrays.copyOf(dependsOn, dependsOn.length + 1);
        merged[dependsOn.length] = addition;
        return merged;
    }

    public static final class SchemaMigrations {

        private final DataSource dataSource;

        private SchemaMigrations(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @PostConstruct
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
