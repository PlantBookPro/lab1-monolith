package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.Tag;
import com.plantarena.tournaments.domain.TagRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
@Transactional
public class JpaTagRepository implements TagRepository {

    private final TagJpaRepository tags;

    public JpaTagRepository(TagJpaRepository tags) {
        this.tags = tags;
    }

    @Override
    public Tag save(Tag tag) {
        TagJpaEntity entity = tags.findById(tag.id())
            .orElseGet(() -> new TagJpaEntity(tag.id(), tag.name(), tag.createdAt()));
        entity.update(tag.name());
        return toDomain(tags.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tag> findById(UUID id) {
        return tags.findById(id).map(JpaTagRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tag> findByName(String name) {
        return tags.findByName(name).map(JpaTagRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Tag> findAll(int offset, int size) {
        return tags.findAllByOrderByNameAscIdAsc(PageRequest.of(offset / size, size,
                Sort.unsorted())).stream().map(JpaTagRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return tags.count();
    }

    @Override
    public void delete(UUID id) {
        tags.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isUsedByTournament(UUID tagId) {
        return tags.isUsedByTournament(tagId);
    }

    private static Tag toDomain(TagJpaEntity entity) {
        return Tag.restore(entity.getId(), entity.getName(), entity.getCreatedAt());
    }
}
