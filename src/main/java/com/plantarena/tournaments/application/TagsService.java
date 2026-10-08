package com.plantarena.tournaments.application;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TagData;
import com.plantarena.tournaments.application.port.in.TagsUseCase;
import com.plantarena.tournaments.domain.Tag;
import com.plantarena.tournaments.domain.TagRepository;
import com.plantarena.tournaments.domain.TournamentRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class TagsService implements TagsUseCase {

    private final TagRepository tags;
    private final TournamentRepository tournaments;
    private final TournamentsAccessPolicy accessPolicy;
    private final Clock clock;

    public TagsService(TagRepository tags, TournamentRepository tournaments,
                       TournamentsAccessPolicy accessPolicy, Clock clock) {
        this.tags = tags;
        this.tournaments = tournaments;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TagData create(CurrentActor actor, String name) {
        accessPolicy.requireModeratorOrAdmin(actor);
        requireFreeName(name, null);
        return TournamentAssembler.toData(tags.save(Tag.create(name, clock.instant())));
    }

    @Override
    @Transactional
    public TagData rename(CurrentActor actor, UUID tagId, String name) {
        accessPolicy.requireAdmin(actor);
        Tag tag = tags.findById(tagId)
            .orElseThrow(() -> new TagNotFoundException("Тег не найден: " + tagId));
        requireFreeName(name, tagId);
        tag.rename(name);
        return TournamentAssembler.toData(tags.save(tag));
    }

    @Override
    @Transactional
    public void delete(CurrentActor actor, UUID tagId) {
        accessPolicy.requireAdmin(actor);
        Tag tag = tags.findById(tagId)
            .orElseThrow(() -> new TagNotFoundException("Тег не найден: " + tagId));
        if (tags.isUsedByTournament(tag.id())) {
            throw new TagInUseException("Тег используется турниром: " + tag.name());
        }
        tags.delete(tag.id());
    }

    @Override
    @Transactional(readOnly = true)
    public TagData get(CurrentActor actor, UUID tagId) {
        accessPolicy.requireIdentified(actor);
        return TournamentAssembler.toData(tags.findById(tagId)
            .orElseThrow(() -> new TagNotFoundException("Тег не найден: " + tagId)));
    }

    @Override
    @Transactional(readOnly = true)
    public TagListResult list(CurrentActor actor, int page, int size) {
        accessPolicy.requireIdentified(actor);
        int offset = page * size;
        List<TagData> items = tags.findAll(offset, size).stream()
            .map(TournamentAssembler::toData)
            .toList();
        return new TagListResult(items, tags.count());
    }

    private void requireFreeName(String name, UUID exceptTagId) {
        tags.findByName(name == null ? "" : name.trim())
            .filter(tag -> !tag.id().equals(exceptTagId))
            .ifPresent(tag -> {
                throw new TagAlreadyExistsException("Тег с именем уже существует: " + tag.name());
            });
    }
}
