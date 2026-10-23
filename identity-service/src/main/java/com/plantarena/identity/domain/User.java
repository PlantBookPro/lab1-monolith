package com.plantarena.identity.domain;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;


public class User {

    private final UUID id;
    private final Email email;
    private String displayName;
    private final String passwordHash;
    private final Set<UserRole> roles = EnumSet.noneOf(UserRole.class);
    private UserStatus status;
    private GeoPoint location;
    private final long version;

    private User(UUID id, Email email, String displayName, String passwordHash,
                 Set<UserRole> roles, UserStatus status, GeoPoint location, long version) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.roles.addAll(roles);
        this.roles.add(UserRole.USER);
        this.status = status;
        this.location = location;
        this.version = version;
    }

    
    public static User registerUser(Email email, String displayName, String passwordHash) {
        return new User(UUID.randomUUID(), email, displayName, passwordHash,
            EnumSet.of(UserRole.USER), UserStatus.ACTIVE, null, 0);
    }

    
    public static User bootstrapAdmin(Email email, String displayName, String passwordHash) {
        return new User(UUID.randomUUID(), email, displayName, passwordHash,
            EnumSet.of(UserRole.USER, UserRole.ADMIN), UserStatus.ACTIVE, null, 0);
    }

    
    public static User restore(UUID id, Email email, String displayName, String passwordHash,
                               Set<UserRole> roles, UserStatus status, GeoPoint location, long version) {
        return new User(id, email, displayName, passwordHash, roles, status, location, version);
    }

    
    public void grantModerator() {
        roles.add(UserRole.MODERATOR);
    }

    
    public void revokeModerator() {
        roles.remove(UserRole.MODERATOR);
    }

    public void changeDisplayName(String newDisplayName) {
        if (newDisplayName == null || newDisplayName.isBlank()) {
            throw new IllegalArgumentException("Имя профиля не может быть пустым");
        }
        this.displayName = newDisplayName.trim();
    }

    public void moveTo(GeoPoint newLocation) {
        this.location = newLocation;
    }

    
    public void deactivate() {
        this.status = UserStatus.DEACTIVATED;
    }

    public UUID id() {
        return id;
    }

    public Email email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public Set<UserRole> roles() {
        return Set.copyOf(roles);
    }

    public UserStatus status() {
        return status;
    }

    public GeoPoint location() {
        return location;
    }

    public long version() {
        return version;
    }
}
