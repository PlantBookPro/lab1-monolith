package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат tournaments (разделы 7, 9, 12.1): окно голосования с зафиксированным
 * составом. Инварианты: интервал [opensAt, closesAt); score участника = сумма
 * текущих голосов за него; один голос субъекта за участника; самоголосование
 * запрещено; повторное закрытие не меняет результатов. Изменяется только
 * через методы корня; время приходит аргументом. Блокировка (FOR UPDATE) —
 * ответственность хранилища, сериализует голоса и закрытие (раздел 12.1).
 */
public final class VotingWindow {

    private final UUID id;
    private final UUID tournamentId;
    private final int sequence;
    private final WindowScope scope;
    private final UUID epochId;
    private final UUID clusterId;
    private final String clusterKey;
    private WindowStatus status;
    private final Instant opensAt;
    private final Instant closesAt;
    private final Map<UUID, WindowParticipant> participantsByEntry = new LinkedHashMap<>();
    private final Map<String, Vote> votesByKey = new LinkedHashMap<>();
    private final Instant createdAt;
    private long version;

    private VotingWindow(UUID id, UUID tournamentId, int sequence, WindowScope scope,
                         UUID epochId, UUID clusterId, String clusterKey, WindowStatus status,
                         Instant opensAt, Instant closesAt, Instant createdAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tournamentId = Objects.requireNonNull(tournamentId, "tournamentId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence >= 1");
        }
        this.sequence = sequence;
        this.status = Objects.requireNonNull(status, "status");
        this.opensAt = Objects.requireNonNull(opensAt, "opensAt");
        this.closesAt = Objects.requireNonNull(closesAt, "closesAt");
        if (!opensAt.isBefore(closesAt)) {
            throw new IllegalArgumentException("opensAt < closesAt (полуоткрытый интервал)");
        }
        this.scope = Objects.requireNonNull(scope, "scope");
        this.epochId = epochId;
        this.clusterId = clusterId;
        this.clusterKey = clusterKey;
        if ((scope == WindowScope.QUALIFICATION) != (epochId != null)) {
            throw new IllegalArgumentException("epochId обязателен только для квалификации");
        }
        if (scope != WindowScope.QUALIFICATION && (clusterId != null || clusterKey != null)) {
            throw new IllegalArgumentException("Кластер — только у квалификационного окна");
        }
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.version = version;
    }

    /**
     * Открыть окно раунда: старт турнира (sequence 1) или закрытие предыдущего
     * (sequence + 1, выжившие, счёт с нуля). Состав фиксируется и не меняется.
     */
    public static VotingWindow open(UUID tournamentId, int sequence,
                                     List<ParticipantSeed> seeds, Instant opensAt,
                                     Instant closesAt, Instant now) {
        Objects.requireNonNull(seeds, "seeds");
        if (seeds.size() < 2) {
            throw new IllegalArgumentException(
                "Окно private-турнира открывается минимум с двумя участниками");
        }
        VotingWindow window = new VotingWindow(UUID.randomUUID(), tournamentId, sequence,
            WindowScope.PRIVATE, null, null, null, WindowStatus.OPEN, opensAt, closesAt,
            now, 0L);
        for (ParticipantSeed seed : seeds) {
            if (window.participantsByEntry.containsKey(seed.entryId())) {
                throw new IllegalArgumentException("Дубликат участника в окне: " + seed.entryId());
            }
            window.participantsByEntry.put(seed.entryId(),
                WindowParticipant.newParticipant(seed.entryId(), seed.userId(), seed.joinedAt()));
        }
        return window;
    }

    /**
     * Квалификационное окно кластера эпохи (раздел 8, алгоритм 2): минимум
     * один участник (алгоритм 4 — единственный проходит без голосов).
     * sequence = номер эпохи; уникальность (epochId, clusterId) — БД.
     */
    public static VotingWindow openQualification(UUID tournamentId, UUID epochId,
                                                 UUID clusterId, String clusterKey,
                                                 int sequence, List<ParticipantSeed> seeds,
                                                 Instant opensAt, Instant closesAt,
                                                 Instant now) {
        return openScoped(WindowScope.QUALIFICATION, tournamentId, sequence, seeds,
            epochId, clusterId, clusterKey, opensAt, closesAt, now);
    }

    /**
     * Финальное окно (раздел 8, алгоритм 6): выжившие предыдущего + новые
     * финалисты; минимум один участник (n=1 — лидер, алгоритм 7).
     */
    public static VotingWindow openFinal(UUID tournamentId, int sequence,
                                         List<ParticipantSeed> seeds, Instant opensAt,
                                         Instant closesAt, Instant now) {
        return openScoped(WindowScope.FINAL, tournamentId, sequence, seeds,
            null, null, null, opensAt, closesAt, now);
    }

    private static VotingWindow openScoped(WindowScope scope, UUID tournamentId, int sequence,
                                           List<ParticipantSeed> seeds, UUID epochId,
                                           UUID clusterId, String clusterKey, Instant opensAt,
                                           Instant closesAt, Instant now) {
        Objects.requireNonNull(seeds, "seeds");
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException(
                "Глобальное окно открывается минимум с одним участником");
        }
        VotingWindow window = new VotingWindow(UUID.randomUUID(), tournamentId, sequence,
            scope, epochId, clusterId, clusterKey, WindowStatus.OPEN, opensAt, closesAt,
            now, 0L);
        for (ParticipantSeed seed : seeds) {
            if (window.participantsByEntry.containsKey(seed.entryId())) {
                throw new IllegalArgumentException("Дубликат участника в окне: " + seed.entryId());
            }
            window.participantsByEntry.put(seed.entryId(),
                WindowParticipant.newParticipant(seed.entryId(), seed.userId(), seed.joinedAt()));
        }
        return window;
    }

    /** Восстановление из хранилища (использует только persistence-адаптер). */
    public static VotingWindow restore(UUID id, UUID tournamentId, int sequence,
                                       WindowScope scope, UUID epochId, UUID clusterId,
                                       String clusterKey, WindowStatus status, Instant opensAt,
                                       Instant closesAt, Instant createdAt, long version,
                                       Collection<WindowParticipant> participants,
                                       Collection<Vote> votes) {
        VotingWindow window = new VotingWindow(id, tournamentId, sequence, scope, epochId,
            clusterId, clusterKey, status, opensAt, closesAt, createdAt, version);
        participants.forEach(participant ->
            window.participantsByEntry.put(participant.entryId(), participant));
        votes.forEach(vote ->
            window.votesByKey.put(voteKey(vote.subjectKey(), vote.entryId()), vote));
        return window;
    }

    /**
     * Установить голос субъекта (PUT, раздел 9); возвращает новый score.
     * Повтор того же значения не меняет счёт, смена — ±2.
     */
    public long castVote(VotingSubject subject, UUID entryId, VoteValue value, Instant now) {
        requireAcceptingVotes(now);
        WindowParticipant participant = participant(entryId);
        if (subject.isUser(participant.userId())) {
            throw new IllegalStateException("Самоголосование запрещено (допущение 6)");
        }
        Vote existing = votesByKey.get(voteKey(subject.subjectKey(), entryId));
        long delta = VoteValue.transitionDelta(existing == null ? null : existing.value(), value);
        if (existing == null) {
            votesByKey.put(voteKey(subject.subjectKey(), entryId),
                Vote.newVote(entryId, subject.subjectKey(), value, now));
        } else {
            existing.update(value, now);
        }
        participant.applyDelta(delta);
        return participant.score();
    }

    /** Удалить голос субъекта (DELETE, раздел 9); идемпотентно; возвращает score. */
    public long removeVote(VotingSubject subject, UUID entryId, Instant now) {
        requireAcceptingVotes(now);
        WindowParticipant participant = participant(entryId);
        Vote existing = votesByKey.remove(voteKey(subject.subjectKey(), entryId));
        if (existing != null) {
            participant.applyDelta(-existing.value().contribution());
        }
        return participant.score();
    }

    /**
     * Закрытие окна (разделы 7, 12.3): фиксирует итоговый рейтинг и результаты
     * участников. Повторный вызов для CLOSED — ошибка состояния; идемпотентность
     * повтора (windowId, sequence) — на уровне use case по статусу. Если после
     * выбывания остаётся один — он WINNER (турнир FINISHED решает use case).
     */
    public CloseOutcome close(Instant now, EliminationAlgorithm algorithm,
                              double eliminationFraction) {
        if (status != WindowStatus.OPEN) {
            throw new IllegalStateException("Окно уже закрыто: " + id);
        }
        if (now.isBefore(closesAt)) {
            throw new IllegalStateException("Окно открыто до " + closesAt);
        }
        status = WindowStatus.CLOSED;
        List<WindowParticipant> ranked = ParticipantRanking.rank(participantsByEntry.values());
        int eliminatedCount = algorithm.eliminatedCount(ranked.size(), eliminationFraction);
        List<UUID> eliminated = new ArrayList<>(eliminatedCount);
        for (int i = 0; i < eliminatedCount; i++) {
            WindowParticipant worst = ranked.get(ranked.size() - 1 - i);
            worst.eliminate();
            eliminated.add(worst.entryId());
        }
        int survivorCount = ranked.size() - eliminatedCount;
        List<UUID> survived = new ArrayList<>(survivorCount);
        UUID winnerEntryId = null;
        for (int i = 0; i < survivorCount; i++) {
            WindowParticipant survivor = ranked.get(i);
            if (survivorCount == 1) {
                survivor.declareWinner();
                winnerEntryId = survivor.entryId();
            } else {
                survivor.survive();
                survived.add(survivor.entryId());
            }
        }
        return new CloseOutcome(List.copyOf(eliminated), List.copyOf(survived), winnerEntryId);
    }

    /**
     * Закрытие квалификационного окна (раздел 8, алгоритмы 3–5): top-1
     * рейтинга — PROMOTED (в финал), остальные — ELIMINATED. Рейтинг:
     * score DESC, joinedAt ASC, entryId ASC (допущение 8).
     */
    public QualificationCloseOutcome closeQualification(Instant now) {
        requireScope(WindowScope.QUALIFICATION);
        requireClosable(now);
        status = WindowStatus.CLOSED;
        List<WindowParticipant> ranked = ParticipantRanking.rank(participantsByEntry.values());
        WindowParticipant top = ranked.get(0);
        top.promote();
        List<UUID> eliminated = new ArrayList<>(ranked.size() - 1);
        for (int i = 1; i < ranked.size(); i++) {
            WindowParticipant worst = ranked.get(i);
            worst.eliminate();
            eliminated.add(worst.entryId());
        }
        return new QualificationCloseOutcome(top.entryId(), List.copyOf(eliminated));
    }

    /**
     * Закрытие финального окна (раздел 8, алгоритмы 7–8): n ≥ 2 — выбывает
     * max(1, floor(n/2)) худших, выжившие SURVIVED; n = 1 — лидер остаётся
     * (SURVIVED без выбывания). Повторное закрытие — ошибка состояния.
     */
    public FinalCloseOutcome closeFinal(Instant now) {
        requireScope(WindowScope.FINAL);
        requireClosable(now);
        status = WindowStatus.CLOSED;
        List<WindowParticipant> ranked = ParticipantRanking.rank(participantsByEntry.values());
        if (ranked.size() == 1) {
            ranked.get(0).survive();
            return new FinalCloseOutcome(List.of(), List.of());
        }
        int eliminatedCount = Math.max(1, ranked.size() / 2);
        List<UUID> eliminated = new ArrayList<>(eliminatedCount);
        for (int i = 0; i < eliminatedCount; i++) {
            WindowParticipant worst = ranked.get(ranked.size() - 1 - i);
            worst.eliminate();
            eliminated.add(worst.entryId());
        }
        List<UUID> survived = new ArrayList<>(ranked.size() - eliminatedCount);
        for (int i = 0; i < ranked.size() - eliminatedCount; i++) {
            ranked.get(i).survive();
            survived.add(ranked.get(i).entryId());
        }
        return new FinalCloseOutcome(List.copyOf(eliminated), List.copyOf(survived));
    }

    private void requireScope(WindowScope expected) {
        if (scope != expected) {
            throw new IllegalStateException("Ожидается scope " + expected + ", текущий: " + scope);
        }
    }

    private void requireClosable(Instant now) {
        if (status != WindowStatus.OPEN) {
            throw new IllegalStateException("Окно уже закрыто: " + id);
        }
        if (now.isBefore(closesAt)) {
            throw new IllegalStateException("Окно открыто до " + closesAt);
        }
    }

    /** Текущий голос субъекта за участника (null — голоса нет). */
    public VoteValue myVote(String subjectKey, UUID entryId) {
        Vote vote = votesByKey.get(voteKey(subjectKey, entryId));
        return vote == null ? null : vote.value();
    }

    /** Принимает ли окно новые голоса/удаления: OPEN и now < closesAt. */
    public boolean isAcceptingVotes(Instant now) {
        return status == WindowStatus.OPEN && now.isBefore(closesAt);
    }

    public boolean hasEntry(UUID entryId) {
        return participantsByEntry.containsKey(entryId);
    }

    /** Владелец участия (проверка самоголосования; денормализация из entry). */
    public UUID userIdOfEntry(UUID entryId) {
        return participant(entryId).userId();
    }

    /** Счёт участника (инвариант: сумма текущих голосов). */
    public long scoreOf(UUID entryId) {
        return participant(entryId).score();
    }

    public Collection<WindowParticipant> participants() {
        return List.copyOf(participantsByEntry.values());
    }

    /** Голоса за участника окна (persistence-маппинг, проверка инварианта). */
    public List<Vote> votesOf(UUID entryId) {
        return votesByKey.values().stream()
            .filter(vote -> vote.entryId().equals(entryId)).toList();
    }

    private void requireAcceptingVotes(Instant now) {
        if (status != WindowStatus.OPEN) {
            throw new IllegalStateException("Голосование в закрытом окне запрещено: " + id);
        }
        if (!now.isBefore(closesAt)) {
            throw new IllegalStateException("Дедлайн окна истёк: " + closesAt);
        }
    }

    private WindowParticipant participant(UUID entryId) {
        WindowParticipant participant = participantsByEntry.get(entryId);
        if (participant == null) {
            throw new IllegalStateException("Участие не входит в окно: " + entryId);
        }
        return participant;
    }

    private static String voteKey(String subjectKey, UUID entryId) {
        return subjectKey + ":" + entryId;
    }

    public UUID id() {
        return id;
    }

    public UUID tournamentId() {
        return tournamentId;
    }

    public int sequence() {
        return sequence;
    }

    public WindowScope scope() {
        return scope;
    }

    public UUID epochId() {
        return epochId;
    }

    public UUID clusterId() {
        return clusterId;
    }

    public String clusterKey() {
        return clusterKey;
    }

    public WindowStatus status() {
        return status;
    }

    public Instant opensAt() {
        return opensAt;
    }

    public Instant closesAt() {
        return closesAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }

    /** Состав нового окна: entry + владелец + joinedAt (из TournamentEntry). */
    public record ParticipantSeed(UUID entryId, UUID userId, Instant joinedAt) {
    }

    /** Итог закрытия: выбывшие; выжившие (≥ 2 → следующий раунд) или победитель. */
    public record CloseOutcome(List<UUID> eliminatedEntryIds, List<UUID> survivedEntryIds,
                               UUID winnerEntryId) {
    }

    /** Итог квалификации: продвинутый в финал и выбывшие (алгоритмы 3–5). */
    public record QualificationCloseOutcome(UUID promotedEntryId, List<UUID> eliminatedEntryIds) {
    }

    /** Итог финала: выбывшие и выжившие (n=1 — оба списка пусты, лидер жив). */
    public record FinalCloseOutcome(List<UUID> eliminatedEntryIds, List<UUID> survivedEntryIds) {
    }

    /** Идентичность по id: JPA-адаптер пересобирает агрегат при чтении. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VotingWindow other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
