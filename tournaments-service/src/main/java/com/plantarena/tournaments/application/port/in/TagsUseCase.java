package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TagData;
import java.util.List;
import java.util.UUID;


public interface TagsUseCase {

    TagData create(CurrentActor actor, String name);

    TagData rename(CurrentActor actor, UUID tagId, String name);

    void delete(CurrentActor actor, UUID tagId);

    TagData get(CurrentActor actor, UUID tagId);

    TagListResult list(CurrentActor actor, int page, int size);

    record TagListResult(List<TagData> items, long total) {
    }
}
