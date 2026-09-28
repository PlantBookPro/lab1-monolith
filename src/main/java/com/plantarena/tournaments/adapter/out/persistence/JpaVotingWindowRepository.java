package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.Vote;
import com.plantarena.tournaments.domain.VoteValue;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import com.plantarena.tournaments.domain.WindowParticipant;
import com.plantarena.tournaments.domain.WindowScope;
import com.plantarena.tournaments.domain.WindowStatus;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-реализация порта VotingWindowRepository: явный маппинг домена и
 * JPA-модели. Состав окна зафиксирован — участники только добавляются;
 * голоса добавляются/обновляются/удаляются (orphanRemoval). Блокировка —
 * PESSIMISTIC_WRITE (раздел 12.1).
 */
@Repository
public class JpaVotingWindowRepository implements VotingWindowRepository {

    private final VotingWindowJpaRepository jpaRepository;

    public JpaVotingWindowRepository(VotingWindowJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public VotingWindow save(VotingWindow window) {
        VotingWindowJpaEntity entity = jpaRepository.findById(window.id())
            .orElseGet(() -> newEntity(window));
        mapState(entity, window);
        jpaRepository.saveAndFlush(entity);
        return window;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VotingWindow> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional
    public Optional<VotingWindow> findByIdForUpdate(UUID id) {
        return jpaRepository.findByIdForUpdate(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findDueForClose(Instant now, int limit) {
        return jpaRepository.findDueForClose(now, PageRequest.of(0, limit));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VotingWindow> findByTournamentId(UUID tournamentId, int offset, int size) {
        return jpaRepository.findByTournamentIdOrderBySequence(tournamentId,
                PageRequest.of(offset / Math.max(size, 1), Math.max(size, 1)))
            .stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByTournamentId(UUID tournamentId) {
        return jpaRepository.countByTournamentId(tournamentId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VotingWindow> findLatestByTournamentId(UUID tournamentId) {
        return jpaRepository.findFirstByTournamentIdOrderBySequenceDesc(tournamentId)
            .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VotingWindow> findAllByTournamentId(UUID tournamentId) {
        return jpaRepository.findAllByTournamentIdOrderBySequence(tournamentId)
            .stream().map(this::toDomain).toList();
    }

    private VotingWindowJpaEntity newEntity(VotingWindow window) {
        VotingWindowJpaEntity entity = new VotingWindowJpaEntity();
        entity.setId(window.id());
        return entity;
    }

    private void mapState(VotingWindowJpaEntity entity, VotingWindow window) {
        entity.setTournamentId(window.tournamentId());
        entity.setSequence(window.sequence());
        entity.setStatus(window.status().name());
        entity.setOpensAt(window.opensAt());
        entity.setClosesAt(window.closesAt());
        entity.setCreatedAt(window.createdAt());
        for (WindowParticipant participant : window.participants()) {
            WindowParticipantJpaEntity participantEntity = entity.getParticipants().stream()
                .filter(existing -> existing.getEntryId().equals(participant.entryId()))
                .findAny()
                .orElseGet(() -> appendParticipant(entity, participant));
            participantEntity.setScore(participant.score());
            participantEntity.setResult(participant.result().name());
            mapVotes(participantEntity, participant, window);
        }
    }

    private WindowParticipantJpaEntity appendParticipant(VotingWindowJpaEntity entity,
                                                         WindowParticipant participant) {
        WindowParticipantJpaEntity participantEntity = new WindowParticipantJpaEntity();
        participantEntity.setId(participant.id());
        participantEntity.setWindow(entity);
        participantEntity.setEntryId(participant.entryId());
        participantEntity.setUserId(participant.userId());
        participantEntity.setJoinedAt(participant.joinedAt());
        entity.getParticipants().add(participantEntity);
        return participantEntity;
    }

    private void mapVotes(WindowParticipantJpaEntity participantEntity,
                          WindowParticipant participant, VotingWindow window) {
        Set<String> currentKeys = new HashSet<>();
        for (Vote vote : window.votesOf(participant.entryId())) {
            currentKeys.add(vote.subjectKey());
            VoteJpaEntity voteEntity = participantEntity.getVotes().stream()
                .filter(existing -> existing.getSubjectKey().equals(vote.subjectKey()))
                .findAny()
                .orElseGet(() -> appendVote(participantEntity, vote));
            voteEntity.setValue(vote.value().name());
            voteEntity.setUpdatedAt(vote.updatedAt());
        }
        participantEntity.getVotes()
            .removeIf(existing -> !currentKeys.contains(existing.getSubjectKey()));
    }

    private VoteJpaEntity appendVote(WindowParticipantJpaEntity participantEntity, Vote vote) {
        VoteJpaEntity voteEntity = new VoteJpaEntity();
        voteEntity.setId(vote.id());
        voteEntity.setParticipant(participantEntity);
        voteEntity.setSubjectKey(vote.subjectKey());
        voteEntity.setValue(vote.value().name());
        voteEntity.setCreatedAt(vote.createdAt());
        voteEntity.setUpdatedAt(vote.updatedAt());
        participantEntity.getVotes().add(voteEntity);
        return voteEntity;
    }

    private VotingWindow toDomain(VotingWindowJpaEntity entity) {
        List<WindowParticipant> participants = entity.getParticipants().stream()
            .map(this::toDomainParticipant).toList();
        List<Vote> votes = entity.getParticipants().stream()
            .flatMap(participant -> participant.getVotes().stream()
                .map(vote -> toDomainVote(participant.getEntryId(), vote)))
            .toList();
        return VotingWindow.restore(entity.getId(), entity.getTournamentId(),
            entity.getSequence(), WindowScope.PRIVATE, null, null, null,
            WindowStatus.valueOf(entity.getStatus()),
            entity.getOpensAt(), entity.getClosesAt(), entity.getCreatedAt(),
            entity.getVersion(), participants, votes);
    }

    private WindowParticipant toDomainParticipant(WindowParticipantJpaEntity entity) {
        return WindowParticipant.restore(entity.getId(), entity.getEntryId(), entity.getUserId(),
            entity.getScore(), com.plantarena.tournaments.domain.ParticipantResult
                .valueOf(entity.getResult()), entity.getJoinedAt());
    }

    private Vote toDomainVote(UUID entryId, VoteJpaEntity entity) {
        return Vote.restore(entity.getId(), entryId, entity.getSubjectKey(),
            VoteValue.valueOf(entity.getValue()), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
