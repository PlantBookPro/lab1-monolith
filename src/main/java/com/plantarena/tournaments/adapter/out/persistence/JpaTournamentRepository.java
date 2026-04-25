package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.CancelReason;
import com.plantarena.tournaments.domain.EliminationAlgorithmKind;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.TournamentStatus;
import com.plantarena.tournaments.domain.TournamentType;
import java.time.Duration;
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
 * Реализация порта TournamentRepository на JPA + PostgreSQL (раздел 14.2).
 * find-or-create + update + saveAndFlush (паттерн JpaPlantRepository):
 * конкурентные старт/отмена ловит @Version в БД. Теги синхронизируются через
 * @ManyToMany (раздел 11).
 */
@Repository
@Transactional
public class JpaTournamentRepository implements TournamentRepository {

    private final TournamentJpaRepository tournaments;
    private final TagJpaRepository tags;

    public JpaTournamentRepository(TournamentJpaRepository tournaments, TagJpaRepository tags) {
        this.tournaments = tournaments;
        this.tags = tags;
    }

    @Override
    public Tournament save(Tournament tournament) {
        TournamentJpaEntity entity = tournaments.findById(tournament.id())
            .orElseGet(() -> new TournamentJpaEntity(tournament.id(), tournament.creatorId(),
                tournament.name(), tournament.type().name(), tournament.algorithm().name(),
                tournament.registrationDeadline(), tournament.roundDuration().toSeconds(),
                tournament.eliminationFraction(), tournament.minParticipants(),
                tournament.createdAt()));
        entity.update(tournament.name(), tournament.description(),
            tournament.status().name(), tournament.registrationDeadline(),
            tournament.roundDuration().toSeconds(), tournament.eliminationFraction(),
            tournament.minParticipants(),
            tournament.cancelReason() == null ? null : tournament.cancelReason().name(),
            new HashSet<>(tags.findAllById(tournament.tagIds())));
        return toDomain(tournaments.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tournament> findById(UUID id) {
        return tournaments.findById(id).map(JpaTournamentRepository::toDomain);
    }

    @Override
    public void delete(UUID id) {
        tournaments.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Tournament> search(TournamentFilter filter) {
        return tournaments.search(filter.admin(), filter.userId(),
                filter.status() == null ? null : filter.status().name(), filter.tagId(),
                PageRequest.of(filter.offset() / filter.size(), filter.size()))
            .stream().map(JpaTournamentRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(TournamentFilter filter) {
        return tournaments.searchCount(filter.admin(), filter.userId(),
            filter.status() == null ? null : filter.status().name(), filter.tagId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Tournament> findDueForStart(Instant now, int limit) {
        return tournaments.findDueForStart(now, PageRequest.of(0, limit)).stream()
            .map(JpaTournamentRepository::toDomain).toList();
    }

    private static Tournament toDomain(TournamentJpaEntity entity) {
        Set<UUID> tagIds = new HashSet<>();
        entity.getTags().forEach(tag -> tagIds.add(tag.getId()));
        return Tournament.restore(entity.getId(), entity.getCreatorId(), entity.getName(),
            entity.getDescription(), TournamentType.valueOf(entity.getType()),
            TournamentStatus.valueOf(entity.getStatus()),
            EliminationAlgorithmKind.valueOf(entity.getAlgorithm()),
            entity.getRegistrationDeadline(), Duration.ofSeconds(entity.getRoundDurationSeconds()),
            entity.getEliminationFraction(), entity.getMinParticipants(),
            entity.getCancelReason() == null ? null : CancelReason.valueOf(entity.getCancelReason()),
            tagIds, entity.getCreatedAt(), entity.getVersion());
    }
}
