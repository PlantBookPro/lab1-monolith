package com.plantarena.feed.adapter.out.persistence;

import com.plantarena.feed.FeedCardRepositoryContractTest;
import com.plantarena.feed.domain.FeedCardRepository;
import com.plantarena.support.PostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Контракт FeedCardRepository на JDBC + PostgreSQL (Testcontainers, hashtextextended).
 * Миграции выполняет Flyway (spring.flyway.*, application-test.yml, схема feed),
 * H2 не используется. Контейнер singleton на JVM: предыдущие контексты
 * коммитят карточки в общую БД, поэтому перед каждым тестом таблица чистится.
 */
@DisplayName("Контракт FeedCardRepository: JDBC + PostgreSQL (Testcontainers)")
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JdbcFeedCardRepository.class)
class JdbcFeedCardRepositoryContractIT extends FeedCardRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcFeedCardRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_карточки_ленты_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from feed.feed_card");
    }

    @Override
    protected FeedCardRepository repository() {
        return repository;
    }
}
