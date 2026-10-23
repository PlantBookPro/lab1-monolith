package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.web.PaginationParams;
import com.plantarena.shared.webflux.Blocking;
import com.plantarena.shared.webflux.ReactiveCurrentActorProvider;
import com.plantarena.tournaments.application.port.in.TagsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
import reactor.core.publisher.Mono;


@RestController
@RequestMapping("/api/v1/tags")
@Tag(name = "tournaments")
@RequiredArgsConstructor
public class TagController {

    private final TagsUseCase tags;
    private final ReactiveCurrentActorProvider actors;

    @PostMapping
    @Operation(operationId = "tags-create", summary = "Создать тег (модератор/админ)")
    public Mono<ResponseEntity<TagResponse>> create(@Valid @RequestBody CreateTagRequest request) {
        return actors.currentActor().flatMap(actor -> Blocking.call(() -> {
                var tag = tags.create(actor, request.name());
                return ResponseEntity
                    .created(URI.create("/api/v1/tags/" + tag.id()))
                    .body(TagResponse.from(tag));
            }));
    }

    @GetMapping
    @Operation(operationId = "tags-list", summary = "Справочник тегов (X-Total-Count)")
    public Mono<ResponseEntity<List<TagResponse>>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return actors.currentActor().flatMap(actor -> Blocking.call(() -> {
                PaginationParams pagination = PaginationParams.of(page, size);
                TagsUseCase.TagListResult result = tags.list(actor, pagination.page(), pagination.size());
                return ResponseEntity.ok()
                    .header("X-Total-Count", String.valueOf(result.total()))
                    .body(result.items().stream().map(TagResponse::from).toList());
            }));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "tags-get", summary = "Тег по id")
    public Mono<TagResponse> get(@PathVariable UUID id) {
        return actors.currentActor()
            .flatMap(actor -> Blocking.call(() -> tags.get(actor, id)))
            .map(TagResponse::from);
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "tags-update", summary = "Переименовать тег (админ)")
    public Mono<TagResponse> update(@PathVariable UUID id,
                                    @Valid @RequestBody UpdateTagRequest request) {
        return actors.currentActor()
            .flatMap(actor -> Blocking.call(() -> tags.rename(actor, id, request.name())))
            .map(TagResponse::from);
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "tags-delete",
        summary = "Удалить тег (админ; используемый турниром — 409)")
    public Mono<ResponseEntity<Void>> delete(@PathVariable UUID id) {
        return actors.currentActor()
            .flatMap(actor -> Blocking.call(() -> {
                tags.delete(actor, id);
                return null;
            }))
            .thenReturn(ResponseEntity.noContent().build());
    }
}
