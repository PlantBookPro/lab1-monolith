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
    private WindowStatus status;
    private final Instant opensAt;
    private final Instant closesAt;
    private final Map<UUID, WindowParticipant> participantsByEntry = new LinkedHashMap<>();
    private final Map<String, Vote> votesByKey = new LinkedHashMap<>();
    private final Instant createdAt;
    private long version;

    private VotingWindow(UUID id, UUID tournamentId, int sequence, WindowStatus status,
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
            WindowStatus.OPEN, opensAt, closesAt, now, 0L);
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
                                       WindowStatus status, Instant opensAt, Instant closesAt,
                                       Instant createdAt, long version,
                                       Collection<WindowParticipant> participants,
                                       Collection<Vote> votes) {
        VotingWindow window = new VotingWindow(id, tournamentId, sequence, status,
            opensAt, closesAt, createdAt, version);
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
