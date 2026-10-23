package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.webflux.Blocking;
import com.plantarena.shared.webflux.ReactiveCurrentActorProvider;
import com.plantarena.tournaments.application.port.in.VotingUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;


@RestController
@RequestMapping("/api/v1/windows")
@Tag(name = "voting")
@RequiredArgsConstructor
public class VotingController {

    private final VotingUseCase voting;
    private final ReactiveCurrentActorProvider actors;

    @PutMapping("/{windowId}/entries/{entryId}/vote")
    @Operation(operationId = "voting-cast-vote",
        summary = "Установить голос LIKE/DISLIKE (участник турнира или гость в глобальном окне)")
    public Mono<ResponseEntity<VoteResponse>> cast(@PathVariable UUID windowId,
                                                    @PathVariable UUID entryId,
                                                    @RequestHeader(value = "X-Guest-Token",
                                                        required = false) String guestToken,
                                                    @Valid @RequestBody VoteRequest request) {
        return actors.currentActor()
            .flatMap(actor -> Blocking.call(() -> voting.cast(actor, guestToken, windowId, entryId,
                request.value())))
            .map(score -> ResponseEntity.ok(new VoteResponse(score)));
    }

    @DeleteMapping("/{windowId}/entries/{entryId}/vote")
    @Operation(operationId = "voting-delete-vote",
        summary = "Удалить свой голос до закрытия окна (204, идемпотентно)")
    public Mono<ResponseEntity<Void>> remove(@PathVariable UUID windowId,
                                              @PathVariable UUID entryId,
                                              @RequestHeader(value = "X-Guest-Token",
                                                  required = false) String guestToken) {
        return actors.currentActor()
            .flatMap(actor -> Blocking.call(() -> {
                voting.remove(actor, guestToken, windowId, entryId);
                return null;
            }))
            .map(v -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{windowId}/entries/{entryId}/my-vote")
    @Operation(operationId = "voting-my-vote",
        summary = "Текущий голос субъекта за участника (value или null)")
    public Mono<ResponseEntity<MyVoteResponse>> myVote(@PathVariable UUID windowId,
                                                        @PathVariable UUID entryId,
                                                        @RequestHeader(value = "X-Guest-Token",
                                                            required = false) String guestToken) {
        return actors.currentActor()
            .flatMap(actor -> Blocking.call(() -> voting.myVote(actor, guestToken, windowId, entryId)))
            .map(vote -> ResponseEntity.ok(new MyVoteResponse(vote.orElse(null))));
    }
}
