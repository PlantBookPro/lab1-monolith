package com.plantarena.tournaments.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Агрегат tournaments (раздел 7): закрытый турнир. Инварианты: параметры
 * валидны (дедлайн в будущем, 0 &lt; доля &lt; 1, minParticipants ≥ 2,
 * длительность положительна); параметры и теги меняются только в DRAFT;
 * описание — безопасное изменение в DRAFT/REGISTRATION_OPEN/RUNNING; старт —
 * только из REGISTRATION_OPEN после дедлайна при READY ≥ minParticipants;
 * отмена — только до RUNNING; отмена активного турнира запрещена. Время
 * приходит аргументом.
 */
public final class Tournament {

    private final UUID id;
    private final UUID creatorId;
    private String name;
    private String description;
    private final TournamentType type;
    private TournamentStatus status;
    private final EliminationAlgorithmKind algorithm;
    private Instant registrationDeadline;
    private Duration roundDuration;
    private double eliminationFraction;
    private int minParticipants;
    private CancelReason cancelReason;
    private final Set<UUID> tagIds = new HashSet<>();
    private final Instant createdAt;
    private long version;

    private Tournament(UUID id, UUID creatorId, String name, String description,
                       TournamentType type, TournamentStatus status,
                       EliminationAlgorithmKind algorithm, Instant registrationDeadline,
                       Duration roundDuration, double eliminationFraction, int minParticipants,
                       CancelReason cancelReason, Set<UUID> tagIds, Instant createdAt,
                       long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.creatorId = Objects.requireNonNull(creatorId, "creatorId");
        this.type = Objects.requireNonNull(type, "type");
        this.status = Objects.requireNonNull(status, "status");
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
        this.cancelReason = cancelReason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.version = version;
        applyParameters(name, description, registrationDeadline, roundDuration,
            eliminationFraction, minParticipants, createdAt);
        this.tagIds.addAll(tagIds == null ? Set.of() : tagIds);
    }

    /** Новый черновик закрытого турнира (создаёт модератор/админ, раздел 13). */
    public static Tournament createDraft(UUID creatorId, String name, String description,
                                         Instant registrationDeadline, Duration roundDuration,
                                         double eliminationFraction, int minParticipants,
                                         Set<UUID> tagIds, Instant now) {
        return new Tournament(UUID.randomUUID(), creatorId, name, description,
            TournamentType.PRIVATE, TournamentStatus.DRAFT,
            EliminationAlgorithmKind.ROUND_ELIMINATION, registrationDeadline, roundDuration,
            eliminationFraction, minParticipants, null, tagIds, now, 0);
    }

    /**
     * Единственный глобальный турнир (раздел 8): фиксированный id, статус
     * RUNNING навсегда; параметры private-режима — заглушки, тайминги — в
     * конфигурации (дизайн итерации 7, решение 1). creator — системный UUID.
     */
    public static Tournament global(UUID id, UUID systemCreatorId, Instant now) {
        return new Tournament(id, systemCreatorId, "Глобальный турнир", null,
            TournamentType.GLOBAL, TournamentStatus.RUNNING,
            EliminationAlgorithmKind.ROUND_ELIMINATION, now.plus(Duration.ofHours(1)),
            Duration.ofHours(1), 0.5, 2, null, Set.of(), now, 0);
    }

    /** Восстановление из хранилища (использует только persistence-адаптер). */
    public static Tournament restore(UUID id, UUID creatorId, String name, String description,
                                     TournamentType type, TournamentStatus status,
                                     EliminationAlgorithmKind algorithm,
                                     Instant registrationDeadline, Duration roundDuration,
                                     double eliminationFraction, int minParticipants,
                                     CancelReason cancelReason, Set<UUID> tagIds,
                                     Instant createdAt, long version) {
        return new Tournament(id, creatorId, name, description, type, status, algorithm,
            registrationDeadline, roundDuration, eliminationFraction, minParticipants,
            cancelReason, tagIds, createdAt, version);
    }

    /** Открыть приём заявок: только из DRAFT и до дедлайна. */
    public void openRegistration(Instant now) {
        requireStatus(TournamentStatus.DRAFT);
        if (!now.isBefore(registrationDeadline)) {
            throw new IllegalStateException("Нельзя открыть регистрацию после дедлайна");
        }
        status = TournamentStatus.REGISTRATION_OPEN;
    }

    /** Старт: после дедлайна при достаточном числе READY-заявок (раздел 7). */
    public void start(Instant now, int readyCount) {
        requireStatus(TournamentStatus.REGISTRATION_OPEN);
        if (now.isBefore(registrationDeadline)) {
            throw new IllegalStateException("Старт возможен только после дедлайна");
        }
        if (readyCount < minParticipants) {
            throw new IllegalStateException(
                "Недостаточно READY-заявок: " + readyCount + " < " + minParticipants);
        }
        status = TournamentStatus.RUNNING;
    }

    /** Явная отмена организатором: только до RUNNING (раздел 7). */
    public void cancel(Instant now) {
        requireStatus(TournamentStatus.DRAFT, TournamentStatus.REGISTRATION_OPEN);
        status = TournamentStatus.CANCELLED;
    }

    /** Автоматическая отмена по дедлайну при нехватке участников. */
    public void cancelForInsufficientParticipants(Instant now) {
        requireStatus(TournamentStatus.REGISTRATION_OPEN);
        status = TournamentStatus.CANCELLED;
        cancelReason = CancelReason.INSUFFICIENT_PARTICIPANTS;
    }

    /** Завершение: победитель определён закрытием окна (раздел 7). */
    public void finish(Instant now) {
        if (type == TournamentType.GLOBAL) {
            throw new IllegalStateException("Глобальный турнир никогда не завершается (раздел 8)");
        }
        requireStatus(TournamentStatus.RUNNING);
        status = TournamentStatus.FINISHED;
    }

    /** Изменение параметров: только в DRAFT (раздел 13). */
    public void updateParameters(String name, Instant registrationDeadline,
                                 Duration roundDuration, double eliminationFraction,
                                 int minParticipants, Instant now) {
        requireStatus(TournamentStatus.DRAFT);
        applyParameters(name, description, registrationDeadline, roundDuration,
            eliminationFraction, minParticipants, now);
    }

    /** Безопасное изменение описания: DRAFT/REGISTRATION_OPEN/RUNNING. */
    public void updateDescription(String description) {
        requireStatus(TournamentStatus.DRAFT, TournamentStatus.REGISTRATION_OPEN,
            TournamentStatus.RUNNING);
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Описание не может быть пустым");
        }
        this.description = description.trim();
    }

    /** Изменение тегов: только в DRAFT (tagIds — параметры турнира). */
    public void replaceTags(Set<UUID> tagIds) {
        requireStatus(TournamentStatus.DRAFT);
        this.tagIds.clear();
        this.tagIds.addAll(tagIds == null ? Set.of() : tagIds);
    }

    /** Приглашать можно в DRAFT/REGISTRATION_OPEN до дедлайна (раздел 7). */
    public boolean canInvite(Instant now) {
        return (status == TournamentStatus.DRAFT || status == TournamentStatus.REGISTRATION_OPEN)
            && now.isBefore(registrationDeadline);
    }

    /** Принимать приглашения можно только в REGISTRATION_OPEN до дедлайна. */
    public boolean isAcceptingNow(Instant now) {
        return status == TournamentStatus.REGISTRATION_OPEN
            && now.isBefore(registrationDeadline);
    }

    private void applyParameters(String name, String description,
                                 Instant registrationDeadline, Duration roundDuration,
                                 double eliminationFraction, int minParticipants, Instant now) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Название турнира обязательно");
        }
        if (registrationDeadline == null || !registrationDeadline.isAfter(now)) {
            throw new IllegalArgumentException("Дедлайн регистрации должен быть в будущем");
        }
        if (roundDuration == null || roundDuration.isZero() || roundDuration.isNegative()) {
            throw new IllegalArgumentException("Длительность раунда должна быть положительной");
        }
        if (eliminationFraction <= 0d || eliminationFraction >= 1d) {
            throw new IllegalArgumentException("Доля выбывания должна быть в (0, 1)");
        }
        if (minParticipants < 2) {
            throw new IllegalArgumentException("minParticipants не меньше 2 (раздел 7)");
        }
        this.name = name.trim();
        this.description = description == null ? null : description.trim();
        this.registrationDeadline = registrationDeadline;
        this.roundDuration = roundDuration;
        this.eliminationFraction = eliminationFraction;
        this.minParticipants = minParticipants;
    }

    private void requireStatus(TournamentStatus... allowed) {
        for (TournamentStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException("Недопустимый статус для операции: " + status);
    }

    public UUID id() {
        return id;
    }

    public UUID creatorId() {
        return creatorId;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public TournamentType type() {
        return type;
    }

    public TournamentStatus status() {
        return status;
    }

    public EliminationAlgorithmKind algorithm() {
        return algorithm;
    }

    public Instant registrationDeadline() {
        return registrationDeadline;
    }

    public Duration roundDuration() {
        return roundDuration;
    }

    public double eliminationFraction() {
        return eliminationFraction;
    }

    public int minParticipants() {
        return minParticipants;
    }

    public CancelReason cancelReason() {
        return cancelReason;
    }

    public Set<UUID> tagIds() {
        return Collections.unmodifiableSet(tagIds);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }
}
