package com.plantarena.identity.adapter.out.persistence;

import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.GeoPoint;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRepository;
import com.plantarena.identity.domain.UserRole;
import com.plantarena.identity.domain.UserStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
@Transactional
@RequiredArgsConstructor
public class JdbcUserRepository implements UserRepository {

    private static final String SELECT_USERS = """
        SELECT u.id, u.email_normalized, u.display_name, u.password_hash, u.status,
               u.latitude, u.longitude, u.version, r.code AS role_code
        FROM identity.app_user u
        LEFT JOIN identity.user_role ur ON ur.user_id = u.id
        LEFT JOIN identity.role r ON r.id = ur.role_id
        """;

    private final JdbcClient jdbcClient;

    @Override
    public User save(User user) {
        int updated = jdbcClient.sql("""
                UPDATE identity.app_user
                SET display_name = :displayName,
                    password_hash = :passwordHash,
                    status = :status,
                    latitude = :latitude,
                    longitude = :longitude,
                    version = version + 1
                WHERE id = :id
                """)
            .param("id", user.id())
            .param("displayName", user.displayName())
            .param("passwordHash", user.passwordHash())
            .param("status", user.status().name())
            .param("latitude", latitudeOf(user))
            .param("longitude", longitudeOf(user))
            .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    INSERT INTO identity.app_user (id, email_normalized, display_name,
                        password_hash, status, latitude, longitude, version)
                    VALUES (:id, :email, :displayName, :passwordHash, :status,
                        :latitude, :longitude, 0)
                    """)
                .param("id", user.id())
                .param("email", user.email().value())
                .param("displayName", user.displayName())
                .param("passwordHash", user.passwordHash())
                .param("status", user.status().name())
                .param("latitude", latitudeOf(user))
                .param("longitude", longitudeOf(user))
                .update();
        }
        replaceRoles(user);
        return findById(user.id()).orElseThrow();
    }

    @Override
    public Optional<User> findById(UUID id) {
        return selectUsers("WHERE u.id = :id")
            .param("id", id)
            .query(asUsersList())
            .stream().findFirst();
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        return selectUsers("WHERE u.email_normalized = :email")
            .param("email", email.value())
            .query(asUsersList())
            .stream().findFirst();
    }

    @Override
    public List<User> findAll(int offset, int limit) {
        return selectUsers("ORDER BY u.id LIMIT :limit OFFSET :offset")
            .param("limit", limit)
            .param("offset", offset)
            .query(asUsersList());
    }

    @Override
    public long count() {
        return jdbcClient.sql("SELECT count(*) FROM identity.app_user")
            .query(Long.class)
            .single();
    }

    private JdbcClient.StatementSpec selectUsers(String tail) {
        return jdbcClient.sql(SELECT_USERS + tail);
    }

    private ResultSetExtractor<List<User>> asUsersList() {
        return rs -> {
            Map<UUID, UserRow> byId = new LinkedHashMap<>();
            while (rs.next()) {
                UUID id = rs.getObject("id", UUID.class);
                UserRow row = byId.get(id);
                if (row == null) {
                    row = readRow(rs);
                    byId.put(id, row);
                }
                String roleCode = rs.getString("role_code");
                if (roleCode != null) {
                    row.roles.add(UserRole.valueOf(roleCode));
                }
            }
            return byId.values().stream().map(UserRow::toDomain).toList();
        };
    }

    private UserRow readRow(ResultSet rs) throws SQLException {
        UserRow row = new UserRow();
        row.id = rs.getObject("id", UUID.class);
        row.email = rs.getString("email_normalized");
        row.displayName = rs.getString("display_name");
        row.passwordHash = rs.getString("password_hash");
        row.status = UserStatus.valueOf(rs.getString("status"));
        row.latitude = rs.getObject("latitude", Double.class);
        row.longitude = rs.getObject("longitude", Double.class);
        row.version = rs.getLong("version");
        return row;
    }

    private void replaceRoles(User user) {
        jdbcClient.sql("DELETE FROM identity.user_role WHERE user_id = :userId")
            .param("userId", user.id())
            .update();
        for (UserRole role : user.roles()) {
            Long roleId = jdbcClient.sql("SELECT id FROM identity.role WHERE code = :code")
                .param("code", role.name())
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                    "Роль отсутствует в справочнике identity.role: " + role));
            jdbcClient.sql("""
                    INSERT INTO identity.user_role (user_id, role_id)
                    VALUES (:userId, :roleId)
                    """)
                .param("userId", user.id())
                .param("roleId", roleId)
                .update();
        }
    }

    private Double latitudeOf(User user) {
        return user.location() == null ? null : user.location().latitude();
    }

    private Double longitudeOf(User user) {
        return user.location() == null ? null : user.location().longitude();
    }

    private static final class UserRow {

        private UUID id;
        private String email;
        private String displayName;
        private String passwordHash;
        private UserStatus status;
        private Double latitude;
        private Double longitude;
        private long version;
        private final EnumSet<UserRole> roles = EnumSet.noneOf(UserRole.class);

        private User toDomain() {
            GeoPoint location = latitude == null || longitude == null
                ? null
                : new GeoPoint(latitude, longitude);
            return User.restore(id, new Email(email), displayName, passwordHash,
                roles, status, location, version);
        }
    }
}
