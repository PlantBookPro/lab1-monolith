package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.GuestSession;
import com.plantarena.tournaments.domain.GuestSessionRepository;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
public class JdbcGuestSessionRepository implements GuestSessionRepository {

    private final JdbcClient jdbcClient;

    public JdbcGuestSessionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public GuestSession save(GuestSession session) {
        int updated = jdbcClient.sql("""
                update tournaments.guest_session
                set token_hash = :tokenHash, created_at = :createdAt, expires_at = :expiresAt
                where id = :id
                """)
            .param("id", session.id())
            .param("tokenHash", session.tokenHash())
            .param("createdAt", Timestamp.from(session.createdAt()))
            .param("expiresAt", Timestamp.from(session.expiresAt()))
            .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    insert into tournaments.guest_session (id, token_hash, created_at, expires_at)
                    values (:id, :tokenHash, :createdAt, :expiresAt)
                    """)
                .param("id", session.id())
                .param("tokenHash", session.tokenHash())
                .param("createdAt", Timestamp.from(session.createdAt()))
                .param("expiresAt", Timestamp.from(session.expiresAt()))
                .update();
        }
        return session;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GuestSession> findByTokenHash(String tokenHash) {
        return jdbcClient.sql("""
                select id, token_hash, created_at, expires_at
                from tournaments.guest_session
                where token_hash = :tokenHash
                """)
            .param("tokenHash", tokenHash)
            .query((rs, rowNum) -> GuestSession.restore(
                rs.getObject("id", UUID.class),
                rs.getString("token_hash"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant()))
            .optional();
    }
}
