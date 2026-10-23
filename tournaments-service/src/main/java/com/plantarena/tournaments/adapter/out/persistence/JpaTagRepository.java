package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.adapter.out.persistence.jpa.TagEntity;
import com.plantarena.tournaments.adapter.out.persistence.jpa.TagJpaRepository;
import com.plantarena.tournaments.adapter.out.persistence.jpa.TournamentJpaRepository;
import com.plantarena.tournaments.domain.Tag;
import com.plantarena.tournaments.domain.TagRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-реализация порта TagRepository. delete снимает связи tournament_tag
 * напрямую (как и прежняя JDBC-реализация), не трогая @Version турниров.
 */
@Repository
@Transactional
public class JpaTagRepository implements TagRepository {

    private final TagJpaRepository jpa;
    private final TournamentJpaRepository tournaments;

    public JpaTagRepository(TagJpaRepository jpa, TournamentJpaRepository tournaments) {
        this.jpa = jpa;
        this.tournaments = tournaments;
    }

    @Override
    public Tag save(Tag tag) {
        TagEntity entity = jpa.findById(tag.id()).orElseGet(() -> {
            TagEntity created = new TagEntity();
            created.setId(tag.id());
            created.setCreatedAt(tag.createdAt());
            return created;
        });
        entity.setName(tag.name());
        // saveAndFlush: tournament_tag ссылается на tag — тег должен быть в БД
        // до flush'а коллекции tagIds у турнира в той же транзакции
        TagEntity saved = jpa.saveAndFlush(entity);
        return Tag.restore(saved.getId(), saved.getName(), saved.getCreatedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tag> findById(UUID id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tag> findByName(String name) {
        return jpa.findByName(name).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Tag> findAll(int offset, int size) {
        return jpa.findAll(PageRequest.of(offset / size, size,
                Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))))
            .map(this::toDomain)
            .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return jpa.count();
    }

    @Override
    public void delete(UUID id) {
        jpa.deleteTournamentLinks(id);
        jpa.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isUsedByTournament(UUID tagId) {
        return tournaments.isUsedByTournament(tagId);
    }

    private Tag toDomain(TagEntity entity) {
        return Tag.restore(entity.getId(), entity.getName(), entity.getCreatedAt());
    }
}
