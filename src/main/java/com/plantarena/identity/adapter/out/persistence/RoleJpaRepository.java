package com.plantarena.identity.adapter.out.persistence;

import com.plantarena.identity.domain.UserRole;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface RoleJpaRepository extends JpaRepository<RoleJpaEntity, Long> {

    Optional<RoleJpaEntity> findByCode(UserRole code);
}
