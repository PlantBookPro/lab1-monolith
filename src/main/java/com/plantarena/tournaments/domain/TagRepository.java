package com.plantarena.tournaments.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Порт репозитория справочника тегов; isUsedByTournament — для удаления (409). */
public interface TagRepository {

    Tag save(Tag tag);

    Optional<Tag> findById(UUID id);

    Optional<Tag> findByName(String name);

    List<Tag> findAll(int offset, int size);

    long count();

    void delete(UUID id);

    /** Используется ли тег хотя бы одним турниром (M2M tournament_tag). */
    boolean isUsedByTournament(UUID tagId);
}
