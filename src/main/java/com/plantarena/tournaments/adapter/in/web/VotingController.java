package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import com.plantarena.tournaments.application.port.in.VotingUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Голосование (раздел 13): PUT устанавливает значение (200 + новый score),
 * DELETE удаляет (204, идемпотентно), my-vote возвращает текущее значение.
 * Права и состояние окна решает use case (401/403/404/409).
 */
@RestController
@RequestMapping("/api/v1/windows")
@Tag(name = "voting")
public class VotingController {

    private final VotingUseCase voting;
    private final CurrentActorProvider currentActorProvider;

    public VotingController(VotingUseCase voting, CurrentActorProvider currentActorProvider) {
        this.voting = voting;
        this.currentActorProvider = currentActorProvider;
    }

    @PutMapping("/{windowId}/entries/{entryId}/vote")
    @Operation(operationId = "voting-cast-vote",
        summary = "Установить голос LIKE/DISLIKE за участника окна (участник турнира, не сам)")
    public ResponseEntity<VoteResponse> cast(@PathVariable UUID windowId,
                                             @PathVariable UUID entryId,
                                             @Valid @RequestBody VoteRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        long score = voting.cast(actor, windowId, entryId, request.value());
        return ResponseEntity.ok(new VoteResponse(score));
    }

    @DeleteMapping("/{windowId}/entries/{entryId}/vote")
    @Operation(operationId = "voting-delete-vote",
        summary = "Удалить свой голос до закрытия окна (204, идемпотентно)")
    public ResponseEntity<Void> remove(@PathVariable UUID windowId, @PathVariable UUID entryId) {
        CurrentActor actor = currentActorProvider.currentActor();
        voting.remove(actor, windowId, entryId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{windowId}/entries/{entryId}/my-vote")
    @Operation(operationId = "voting-my-vote",
        summary = "Текущий голос субъекта за участника (value или null)")
    public ResponseEntity<MyVoteResponse> myVote(@PathVariable UUID windowId,
                                                 @PathVariable UUID entryId) {
        CurrentActor actor = currentActorProvider.currentActor();
        return ResponseEntity.ok(
            new MyVoteResponse(voting.myVote(actor, windowId, entryId).orElse(null)));
    }
}
