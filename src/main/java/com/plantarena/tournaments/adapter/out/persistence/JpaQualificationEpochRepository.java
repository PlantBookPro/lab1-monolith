package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.EpochStatus;
import com.plantarena.tournaments.domain.QualificationEpoch;
import com.plantarena.tournaments.domain.QualificationEpochRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
public class JpaQualificationEpochRepository implements QualificationEpochRepository {

    private final QualificationEpochJpaRepository jpaRepository;

    public JpaQualificationEpochRepository(QualificationEpochJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public QualificationEpoch save(QualificationEpoch epoch) {
        QualificationEpochJpaEntity entity = jpaRepository.findById(epoch.id())
            .orElseGet(() -> newEntity(epoch));
        entity.setTournamentId(epoch.tournamentId());
        entity.setSequence(epoch.sequence());
        entity.setStatus(epoch.status().name());
        entity.setOpensAt(epoch.opensAt());
        entity.setClosesAt(epoch.closesAt());
        entity.setCreatedAt(epoch.createdAt());
        jpaRepository.saveAndFlush(entity);
        return epoch;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QualificationEpoch> findById(UUID id) {
        return jpaRepository.findById(id).map(JpaQualificationEpochRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QualificationEpoch> findOpenByTournamentId(UUID tournamentId) {
        return jpaRepository
            .findFirstByTournamentIdAndStatusOrderBySequenceDesc(
                tournamentId, EpochStatus.OPEN.name())
            .map(JpaQualificationEpochRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QualificationEpoch> findLastByTournamentId(UUID tournamentId) {
        return jpaRepository.findFirstByTournamentIdOrderBySequenceDesc(tournamentId)
            .map(JpaQualificationEpochRepository::toDomain);
    }

    private QualificationEpochJpaEntity newEntity(QualificationEpoch epoch) {
        QualificationEpochJpaEntity entity = new QualificationEpochJpaEntity();
        entity.setId(epoch.id());
        return entity;
    }

    private static QualificationEpoch toDomain(QualificationEpochJpaEntity entity) {
        return QualificationEpoch.restore(entity.getId(), entity.getTournamentId(),
            entity.getSequence(), EpochStatus.valueOf(entity.getStatus()),
            entity.getOpensAt(), entity.getClosesAt(), entity.getCreatedAt(),
            entity.getVersion());
    }
}
