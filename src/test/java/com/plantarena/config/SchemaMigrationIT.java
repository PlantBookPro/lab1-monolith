package com.plantarena.config;

import com.plantarena.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Flyway: у каждого контекста своя схема и своя история миграций")
class SchemaMigrationIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void у_каждого_контекста_своя_схема_с_историей_миграций() {
        for (String schema : SchemaMigrationConfig.CONTEXT_SCHEMAS) {
            Integer historyTables = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables"
                    + " where table_schema = ? and table_name = 'flyway_schema_history'",
                Integer.class, schema);
            assertThat(historyTables).as("схема %s", schema).isEqualTo(1);
        }
    }

    @Test
    void миграция_v1_применена_в_каждой_схеме() {
        for (String schema : SchemaMigrationConfig.CONTEXT_SCHEMAS) {
            Integer applied = jdbcTemplate.queryForObject(
                "select count(*) from " + schema + ".flyway_schema_history where success",
                Integer.class);
            assertThat(applied).as("схема %s", schema).isGreaterThanOrEqualTo(1);
        }
    }
}
