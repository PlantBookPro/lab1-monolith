package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.support.PostgresSupport;
import com.plantarena.tournaments.InvitationRepositoryContractTest;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.TournamentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Контракт InvitationRepository на JDBC + PostgreSQL (UNIQUE-пара — БД). */
@DisplayName("Контракт InvitationRepository: JDBC + PostgreSQL (Testcontainers)")
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JdbcTournamentRepository.class,
    JdbcInvitationRepository.class, JdbcTagRepository.class})
class JdbcInvitationRepositoryContractIT extends InvitationRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcInvitationRepository repository;

    @Autowired
    private JdbcTournamentRepository tournaments;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_приглашения_от_предыдущих_контекстов() {
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
    protected InvitationRepository repository() {
        return repository;
    }

    @Override
    protected TournamentRepository tournaments() {
        return tournaments;
    }
}
