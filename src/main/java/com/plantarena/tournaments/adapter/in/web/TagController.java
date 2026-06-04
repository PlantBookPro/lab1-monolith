package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import com.plantarena.shared.web.PaginationParams;
import com.plantarena.tournaments.application.port.in.TagsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Справочник тегов (раздел 13): создание — M/A, изменение/удаление — A
 * (используемый тег не удаляется — 409), чтение — все идентифицированные.
 */
@RestController
@RequestMapping("/api/v1/tags")
@Tag(name = "tournaments")
public class TagController {

    private final TagsUseCase tags;
    private final CurrentActorProvider currentActorProvider;

    public TagController(TagsUseCase tags, CurrentActorProvider currentActorProvider) {
        this.tags = tags;
        this.currentActorProvider = currentActorProvider;
    }

    @PostMapping
    @Operation(operationId = "tags-create", summary = "Создать тег (модератор/админ)")
    public ResponseEntity<TagResponse> create(@Valid @RequestBody CreateTagRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        var tag = tags.create(actor, request.name());
        return ResponseEntity
            .created(URI.create("/api/v1/tags/" + tag.id()))
            .body(TagResponse.from(tag));
    }

    @GetMapping
    @Operation(operationId = "tags-list", summary = "Справочник тегов (X-Total-Count)")
    public ResponseEntity<List<TagResponse>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        TagsUseCase.TagListResult result = tags.list(actor, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(result.items().stream().map(TagResponse::from).toList());
    }

    @GetMapping("/{id}")
    @Operation(operationId = "tags-get", summary = "Тег по id")
    public TagResponse get(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return TagResponse.from(tags.get(actor, id));
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "tags-update", summary = "Переименовать тег (админ)")
    public TagResponse update(@PathVariable UUID id,
                              @Valid @RequestBody UpdateTagRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        return TagResponse.from(tags.rename(actor, id, request.name()));
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "tags-delete",
        summary = "Удалить тег (админ; используемый турниром — 409)")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        tags.delete(actor, id);
        return ResponseEntity.noContent().build();
    }
}
