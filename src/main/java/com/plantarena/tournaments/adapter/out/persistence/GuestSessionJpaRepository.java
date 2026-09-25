package com.plantarena.tournaments.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface GuestSessionJpaRepository extends JpaRepository<GuestSessionJpaEntity, UUID> {

    Optional<GuestSessionJpaEntity> findByTokenHash(String tokenHash);
}
