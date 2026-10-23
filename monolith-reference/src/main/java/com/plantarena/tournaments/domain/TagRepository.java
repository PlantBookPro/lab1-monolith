package com.plantarena.tournaments.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;


public interface TagRepository {

    Tag save(Tag tag);

    Optional<Tag> findById(UUID id);

    Optional<Tag> findByName(String name);

    List<Tag> findAll(int offset, int size);

    long count();

    void delete(UUID id);

    
    boolean isUsedByTournament(UUID tagId);
}
