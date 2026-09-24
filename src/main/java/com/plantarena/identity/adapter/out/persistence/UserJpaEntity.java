package com.plantarena.identity.adapter.out.persistence;

import com.plantarena.identity.domain.UserRole;
import com.plantarena.identity.domain.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * JPA-модель app_user (схема identity, раздел 11). Маппинг на домен явный (UserMapper).
 */
@Entity
@Table(name = "app_user", schema = "identity")
public class UserJpaEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "email_normalized", nullable = false, unique = true)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_role", schema = "identity",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<RoleJpaEntity> roles = new LinkedHashSet<>();

    @Version
    @Column(nullable = false)
    private long version;

    protected UserJpaEntity() {
    }

    UserJpaEntity(UUID id, String email) {
        this.id = id;
        this.email = email;
    }

    void update(String displayName, String passwordHash, UserStatus status,
                Double latitude, Double longitude) {
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.status = status;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    UUID id() {
        return id;
    }

    String email() {
        return email;
    }

    String displayName() {
        return displayName;
    }

    String passwordHash() {
        return passwordHash;
    }

    UserStatus status() {
        return status;
    }

    Double latitude() {
        return latitude;
    }

    Double longitude() {
        return longitude;
    }

    Set<RoleJpaEntity> roles() {
        return roles;
    }

    long versionValue() {
        return version;
    }
}
