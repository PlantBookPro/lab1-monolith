package com.plantarena.identity.adapter.out.persistence;

import com.plantarena.identity.domain.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA-модель справочника role (схема identity): строки засеиваются миграцией V2.
 */
@Entity
@Table(name = "role", schema = "identity")
public class RoleJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    private UserRole code;

    protected RoleJpaEntity() {
    }

    UserRole code() {
        return code;
    }
}
