package com.plantarena.feed.adapter.in.web;

import com.plantarena.feed.application.port.in.ProjectWindowUseCase;
import com.plantarena.shared.webflux.Blocking;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/internal/feed/projections")
@RequiredArgsConstructor
public class InternalProjectionController {

    private final ProjectWindowUseCase projectWindow;

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> onWindowOpened(@RequestBody WindowProjectionRequest request) {
        return Blocking.call(() -> {
            projectWindow.onWindowOpened(request.toCommand());
            return null;
        }).then();
    }

    @DeleteMapping("/{windowId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> onWindowClosed(@PathVariable UUID windowId) {
        return Blocking.call(() -> {
            projectWindow.onWindowClosed(windowId);
            return null;
        }).then();
    }

    public record WindowProjectionRequest(UUID windowId, UUID tournamentId, String scope, UUID clusterId,
                                          Instant closesAt, List<CardSeed> cards) {
        public record CardSeed(UUID entryId, UUID userId, UUID plantId, Instant joinedAt) {}

        public ProjectWindowUseCase.WindowCardsCommand toCommand() {
            return new ProjectWindowUseCase.WindowCardsCommand(windowId, tournamentId, scope, clusterId,
                closesAt, cards.stream().map(c -> new ProjectWindowUseCase.CardSeed(
                    c.entryId(), c.userId(), c.plantId(), c.joinedAt())).toList());
        }
    }
}
