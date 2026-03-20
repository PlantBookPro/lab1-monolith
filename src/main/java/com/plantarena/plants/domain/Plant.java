package com.plantarena.plants.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат plants (раздел 6): заявка «это моё растение» на конкретном изображении.
 * Инварианты: asset и отпечаток неизменяемы после подачи (мутаторов нет);
 * переходы модерации PENDING → APPROVED/REJECTED однократны; DEAD необратим;
 * архивация скрывает растение, сохраняя запреты и историю (раздел 13).
 * Время приходит аргументом (Instant now), не Instant.now().
 */
public final class Plant {

    private static final int TITLE_MAX = 100;

    private final UUID id;
    private final UUID ownerId;
    private final UUID assetId;
    private final ImageFingerprint fingerprint;
    private String title;
    private ModerationStatus moderationStatus;
    private String moderationReason;
    private LifeStatus lifeStatus;
    private final Instant createdAt;
    private Instant diedAt;
    private Instant archivedAt;
    private long version;

    private Plant(UUID id, UUID ownerId, UUID assetId, ImageFingerprint fingerprint, String title,
                  ModerationStatus moderationStatus, String moderationReason, LifeStatus lifeStatus,
                  Instant createdAt, Instant diedAt, Instant archivedAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.assetId = Objects.requireNonNull(assetId, "assetId");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.title = requireTitle(title);
        this.moderationStatus = Objects.requireNonNull(moderationStatus, "moderationStatus");
        this.moderationReason = moderationReason;
        this.lifeStatus = Objects.requireNonNull(lifeStatus, "lifeStatus");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (lifeStatus == LifeStatus.DEAD && diedAt == null) {
            throw new IllegalArgumentException("DEAD требует diedAt");
        }
        this.diedAt = diedAt;
        this.archivedAt = archivedAt;
        this.version = version;
    }

    /** Подача заявки: новый Plant всегда PENDING/ALIVE (раздел 6). */
    public static Plant submit(UUID ownerId, UUID assetId, ImageFingerprint fingerprint,
                               String title, Instant now) {
        return new Plant(UUID.randomUUID(), ownerId, assetId, fingerprint, title,
            ModerationStatus.PENDING, null, LifeStatus.ALIVE, now, null, null, 0);
    }

    /** Восстановление из хранилища с сохранением id и version (JPA-адаптер). */
    public static Plant restore(UUID id, UUID ownerId, UUID assetId, ImageFingerprint fingerprint,
                                String title, ModerationStatus moderationStatus, String moderationReason,
                                LifeStatus lifeStatus, Instant createdAt, Instant diedAt,
                                Instant archivedAt, long version) {
        return new Plant(id, ownerId, assetId, fingerprint, title, moderationStatus,
            moderationReason, lifeStatus, createdAt, diedAt, archivedAt, version);
    }

    /**
     * Решение модерации. Идемпотентно для того же решения (повтор доставки);
     * конфликтующее решение по решённой заявке — ошибка (устаревший результат
     * не применяется, раздел 6).
     *
     * @return true, если состояние изменилось
     */
    public boolean applyDecision(ModerationStatus decision, String reason) {
        if (moderationStatus == decision) {
            return false;
        }
        if (moderationStatus != ModerationStatus.PENDING) {
            throw new PlantAlreadyDecidedException(
                "Решение по заявке уже зафиксировано: " + moderationStatus);
        }
        this.moderationStatus = decision;
        this.moderationReason = reason;
        return true;
    }

    /** Переименование: только title, asset/owner/status недоступны (раздел 13). */
    public void rename(String newTitle) {
        this.title = requireTitle(newTitle);
    }

    /** Архивация: скрытие с сохранением истории; идемпотентна. */
    public boolean archive(Instant now) {
        if (archivedAt != null) {
            return false;
        }
        this.archivedAt = now;
        return true;
    }

    /** Гибель: ALIVE → DEAD необратим; идемпотентна для повторов доставки. */
    public boolean die(Instant now) {
        if (lifeStatus == LifeStatus.DEAD) {
            return false;
        }
        this.lifeStatus = LifeStatus.DEAD;
        this.diedAt = now;
        return true;
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public UUID assetId() {
        return assetId;
    }

    public ImageFingerprint fingerprint() {
        return fingerprint;
    }

    public String title() {
        return title;
    }

    public ModerationStatus moderationStatus() {
        return moderationStatus;
    }

    public String moderationReason() {
        return moderationReason;
    }

    public LifeStatus lifeStatus() {
        return lifeStatus;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant diedAt() {
        return diedAt;
    }

    public Instant archivedAt() {
        return archivedAt;
    }

    public long version() {
        return version;
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank() || title.trim().length() > TITLE_MAX) {
            throw new IllegalArgumentException(
                "Название растения: непустое, до " + TITLE_MAX + " символов");
        }
        return title.trim();
    }
}
