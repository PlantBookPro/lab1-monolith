package com.plantarena.feed.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.feed.FeedCardRepositoryContractTest;
import com.plantarena.feed.domain.FeedCardRepository;
import com.plantarena.support.PostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Контракт FeedCardRepository на JPA + PostgreSQL (Testcontainers, hashtextextended). */
@DisplayName("Контракт FeedCardRepository: JPA + PostgreSQL (Testcontainers)")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JpaFeedCardRepository.class})
class JpaFeedCardRepositoryContractIT extends FeedCardRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JpaFeedCardRepository repository;

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
