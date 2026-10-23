package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.adapter.out.persistence.jpa.TournamentEntity;
import com.plantarena.tournaments.adapter.out.persistence.jpa.TournamentJpaRepository;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.TournamentStatus;
import com.plantarena.tournaments.domain.TournamentType;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
        return jpa.findAll(toSpecification(filter), toPageRequest(filter))
            .map(this::toDomain)
            .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(TournamentFilter filter) {
        return jpa.count(toSpecification(filter));
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

    private Specification<TournamentEntity> toSpecification(TournamentFilter filter) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("type"), TournamentType.PRIVATE));
            if (!filter.admin() && filter.userId() != null) {
                predicates.add(builder.equal(root.get("creatorId"), filter.userId()));
            }
            if (filter.status() != null) {
                predicates.add(builder.equal(root.get("status"), filter.status()));
            }
            if (filter.tagId() != null) {
                predicates.add(builder.isMember(filter.tagId(), root.get("tagIds")));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private PageRequest toPageRequest(TournamentFilter filter) {
        return PageRequest.of(filter.offset() / filter.size(), filter.size(),
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
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
