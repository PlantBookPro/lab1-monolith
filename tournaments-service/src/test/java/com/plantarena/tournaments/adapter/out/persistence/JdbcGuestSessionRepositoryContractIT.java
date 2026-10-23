package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.support.PostgresSupport;
import com.plantarena.tournaments.GuestSessionRepositoryContractTest;
import com.plantarena.tournaments.domain.GuestSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Контракт GuestSessionRepository на JDBC + PostgreSQL (Testcontainers). */
@DisplayName("Контракт GuestSessionRepository: JDBC + PostgreSQL (Testcontainers)")
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JdbcGuestSessionRepository.class})
class JdbcGuestSessionRepositoryContractIT extends GuestSessionRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcGuestSessionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_гостевые_сессии_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from tournaments.guest_session");
    }

    @Override
    protected GuestSessionRepository repository() {
        return repository;
    }
}
