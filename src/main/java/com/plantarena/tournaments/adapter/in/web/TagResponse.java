package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.tournaments.api.TagData;
import java.util.UUID;

/** Ответ REST по тегу справочника (раздел 13). */
public record TagResponse(UUID id, String name) {

    public static TagResponse from(TagData data) {
        return new TagResponse(data.id(), data.name());
    }
}
