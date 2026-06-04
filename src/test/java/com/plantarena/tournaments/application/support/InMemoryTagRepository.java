package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.domain.Tag;
import com.plantarena.tournaments.domain.TagRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory фейк TagRepository: isUsedByTournament — по tagIds турниров. */
public class InMemoryTagRepository implements TagRepository {

    public final Map<UUID, Tag> tags = new ConcurrentHashMap<>();
    private final InMemoryTournamentRepository tournaments;

    public InMemoryTagRepository() {
        this(null);
    }

    public InMemoryTagRepository(InMemoryTournamentRepository tournaments) {
        this.tournaments = tournaments;
    }

    @Override
    public Tag save(Tag tag) {
        tags.put(tag.id(), tag);
        return tag;
    }

    @Override
    public Optional<Tag> findById(UUID id) {
        return Optional.ofNullable(tags.get(id));
    }

    @Override
    public Optional<Tag> findByName(String name) {
        return tags.values().stream()
            .filter(tag -> tag.name().equals(name))
            .findFirst();
    }

    @Override
    public List<Tag> findAll(int offset, int size) {
        return tags.values().stream()
            .sorted(Comparator.comparing(Tag::name))
            .skip(offset).limit(size).toList();
    }

    @Override
    public long count() {
        return tags.size();
    }

    @Override
    public void delete(UUID id) {
        tags.remove(id);
    }

    @Override
    public boolean isUsedByTournament(UUID tagId) {
        return tournaments != null && tournaments.tournaments.values().stream()
            .anyMatch(tournament -> tournament.tagIds().contains(tagId));
    }
}
