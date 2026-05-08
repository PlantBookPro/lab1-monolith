package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.support.PostgresSupport;
import com.plantarena.tournaments.VotingWindowRepositoryContractTest;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Контракт VotingWindowRepository на JPA + PostgreSQL (Testcontainers, раздел
 * 14.2). Миграции выполняет SchemaMigrationConfig (ADR-003). Турнир для FK
 * voting_window → tournament создаётся через JpaTournamentRepository
 * (newTournamentId, вариант (а) брифа).
 */
@DisplayName("Контракт VotingWindowRepository: JPA + PostgreSQL (Testcontainers)")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JpaVotingWindowRepository.class,
    JpaTournamentRepository.class})
class JpaVotingWindowRepositoryContractIT extends VotingWindowRepositoryContractTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JpaVotingWindowRepository repository;

    @Autowired
    private JpaTournamentRepository tournaments;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_окна_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from tournaments.vote");
        jdbcTemplate.update("delete from tournaments.window_participant");
        jdbcTemplate.update("delete from tournaments.voting_window");
        jdbcTemplate.update("delete from tournaments.tournament_tag");
        jdbcTemplate.update("delete from tournaments.invitation");
        jdbcTemplate.update("delete from tournaments.tournament_entry");
        jdbcTemplate.update("delete from tournaments.qualification_epoch");
        jdbcTemplate.update("delete from tournaments.tournament");
        jdbcTemplate.update("delete from tournaments.tag");
    }

    @Override
    protected VotingWindowRepository repository() {
        return repository;
    }

    @Override
    protected UUID newTournamentId() {
        Tournament tournament = Tournament.createDraft(UUID.randomUUID(), "Т", null,
            NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2, Set.of(), NOW);
        return tournaments.save(tournament).id();
    }
}
