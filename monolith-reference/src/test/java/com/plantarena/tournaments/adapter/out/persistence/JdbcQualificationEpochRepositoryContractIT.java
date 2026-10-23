package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.support.PostgresSupport;
import com.plantarena.tournaments.QualificationEpochRepositoryContractTest;
import com.plantarena.tournaments.domain.QualificationEpochRepository;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Контракт QualificationEpochRepository на JDBC + PostgreSQL (Testcontainers). */
@DisplayName("Контракт QualificationEpochRepository: JDBC + PostgreSQL (Testcontainers)")
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JdbcQualificationEpochRepository.class,
    JdbcTournamentRepository.class})
class JdbcQualificationEpochRepositoryContractIT extends QualificationEpochRepositoryContractTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcQualificationEpochRepository repository;

    @Autowired
    private JdbcTournamentRepository tournaments;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_эпохи_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from tournaments.vote");
        jdbcTemplate.update("delete from tournaments.window_participant");
        jdbcTemplate.update("delete from tournaments.voting_window");
        jdbcTemplate.update("delete from tournaments.qualification_epoch");
        jdbcTemplate.update("delete from tournaments.tournament_tag");
        jdbcTemplate.update("delete from tournaments.invitation");
        jdbcTemplate.update("delete from tournaments.tournament_entry");
        jdbcTemplate.update("delete from tournaments.tournament");
        jdbcTemplate.update("delete from tournaments.tag");
    }

    @Override
    protected QualificationEpochRepository repository() {
        return repository;
    }

    @Override
    protected UUID newTournamentId() {
        Tournament tournament = Tournament.createDraft(UUID.randomUUID(), "Т", null,
            NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2, Set.of(), NOW);
        return tournaments.save(tournament).id();
    }
}
