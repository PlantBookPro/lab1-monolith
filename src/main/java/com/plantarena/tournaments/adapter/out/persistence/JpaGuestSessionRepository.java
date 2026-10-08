package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.GuestSession;
import com.plantarena.tournaments.domain.GuestSessionRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
public class JpaGuestSessionRepository implements GuestSessionRepository {

    private final GuestSessionJpaRepository jpaRepository;

    public JpaGuestSessionRepository(GuestSessionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public GuestSession save(GuestSession session) {
        GuestSessionJpaEntity entity = new GuestSessionJpaEntity();
        entity.setId(session.id());
        entity.setTokenHash(session.tokenHash());
        entity.setCreatedAt(session.createdAt());
        entity.setExpiresAt(session.expiresAt());
        jpaRepository.saveAndFlush(entity);
        return session;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GuestSession> findByTokenHash(String tokenHash) {
        return jpaRepository.findByTokenHash(tokenHash)
            .map(entity -> GuestSession.restore(entity.getId(), entity.getTokenHash(),
                entity.getCreatedAt(), entity.getExpiresAt()));
    }
}
