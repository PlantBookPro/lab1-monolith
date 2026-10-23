package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.adapter.out.persistence.jpa.TournamentEntity;
import com.plantarena.tournaments.adapter.out.persistence.jpa.TournamentJpaRepository;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.TournamentStatus;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-реализация порта TournamentRepository (ADR-014): Hibernate + JpaTransactionManager,
 * остальные JdbcClient-репозитории контекста участвуют в тех же транзакциях.
 * Маппинг — только через фабрику Tournament.restore(...) и геттеры домена.
 */
@Repository
@Transactional
public class JpaTournamentRepository implements TournamentRepository {

    private final TournamentJpaRepository jpa;

    public JpaTournamentRepository(TournamentJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Tournament save(Tournament tournament) {
        TournamentEntity entity = jpa.findById(tournament.id()).orElseGet(() -> newEntity(tournament));
        copyMutableFields(tournament, entity);
        // saveAndFlush: оставшиеся JdbcClient-репозитории контекста пишут в той же
        // транзакции (ADR-014) и должны видеть турнир сразу (FK invitation/entry/window)
        TournamentEntity saved = jpa.saveAndFlush(entity);
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tournament> findById(UUID id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public void delete(UUID id) {
        jpa.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Tournament> search(TournamentFilter filter) {
        return jpa.search(filter.admin(), filter.userId(),
                filter.status() == null ? null : filter.status().name(), filter.tagId(),
                PageRequest.of(filter.offset() / filter.size(), filter.size()))
            .map(this::toDomain)
            .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(TournamentFilter filter) {
        return jpa.countForSearch(filter.admin(), filter.userId(),
                filter.status() == null ? null : filter.status().name(), filter.tagId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Tournament> findDueForStart(Instant now, int limit) {
        return jpa.findDueForStart(TournamentStatus.REGISTRATION_OPEN, now,
                PageRequest.of(0, limit))
            .stream()
            .map(this::toDomain)
            .toList();
    }

    private TournamentEntity newEntity(Tournament tournament) {
        TournamentEntity entity = new TournamentEntity();
        entity.setId(tournament.id());
        entity.setCreatorId(tournament.creatorId());
        entity.setType(tournament.type());
        entity.setAlgorithm(tournament.algorithm().name());
        entity.setCreatedAt(tournament.createdAt());
        return entity;
    }

    private void copyMutableFields(Tournament tournament, TournamentEntity entity) {
        entity.setName(tournament.name());
        entity.setDescription(tournament.description());
        entity.setStatus(tournament.status());
        entity.setRegistrationDeadline(tournament.registrationDeadline());
        entity.setRoundDuration(tournament.roundDuration());
        entity.setEliminationFraction(tournament.eliminationFraction());
        entity.setMinParticipants(tournament.minParticipants());
        entity.setCancelReason(tournament.cancelReason());
        entity.getTagIds().clear();
        entity.getTagIds().addAll(new HashSet<>(tournament.tagIds()));
    }

    private Tournament toDomain(TournamentEntity entity) {
        return Tournament.restore(
            entity.getId(),
            entity.getCreatorId(),
            entity.getName(),
            entity.getDescription(),
            entity.getType(),
            entity.getStatus(),
            com.plantarena.tournaments.domain.EliminationAlgorithmKind
                .valueOf(entity.getAlgorithm()),
            entity.getRegistrationDeadline(),
            entity.getRoundDuration(),
            entity.getEliminationFraction(),
            entity.getMinParticipants(),
            entity.getCancelReason(),
            entity.getTagIds(),
            entity.getCreatedAt(),
            entity.getVersion());
    }
}
