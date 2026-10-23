package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.web.PaginationParams;
import com.plantarena.shared.webflux.Blocking;
import com.plantarena.shared.webflux.ReactiveCurrentActorProvider;
import com.plantarena.tournaments.application.UnknownGlobalScopeException;
import com.plantarena.tournaments.application.port.in.GetGlobalInfoUseCase;
import com.plantarena.tournaments.application.port.in.GetGlobalLeaderboardUseCase;
import com.plantarena.tournaments.application.port.in.GetMyGlobalEntryUseCase;
import com.plantarena.tournaments.application.port.in.ListGlobalClustersUseCase;
import com.plantarena.tournaments.application.port.in.SubmitGlobalEntryUseCase;
import com.plantarena.tournaments.application.port.in.WithdrawGlobalEntryUseCase;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;


@RestController
@RequestMapping("/api/v1")
@Tag(name = "global")
@RequiredArgsConstructor
public class GlobalController {

    private final GetGlobalInfoUseCase globalInfo;
    private final ListGlobalClustersUseCase listClusters;
    private final GetGlobalLeaderboardUseCase leaderboard;
    private final SubmitGlobalEntryUseCase submitEntry;
    private final WithdrawGlobalEntryUseCase withdrawEntry;
    private final GetMyGlobalEntryUseCase myEntry;
    private final ReactiveCurrentActorProvider actors;

    @GetMapping("/global")
    @Operation(operationId = "global-get-info",
        summary = "Конфигурация и текущие окна глобального турнира (публично)")
    public Mono<GlobalInfoResponse> info() {
        return Blocking.call(() -> {
            GetGlobalInfoUseCase.GlobalInfo info = globalInfo.globalInfo();
            return new GlobalInfoResponse(info.epochDurationSeconds(),
                info.finalWindowDurationSeconds(),
                info.currentEpoch() == null ? null
                    : new GlobalInfoResponse.EpochInfo(info.currentEpoch().id(),
                        info.currentEpoch().sequence(), info.currentEpoch().opensAt(),
                        info.currentEpoch().closesAt()),
                info.currentFinalWindow() == null ? null
                    : new GlobalInfoResponse.WindowInfo(info.currentFinalWindow().id(),
                        info.currentFinalWindow().sequence(), info.currentFinalWindow().opensAt(),
                        info.currentFinalWindow().closesAt()));
        });
    }

    @PostMapping("/global/entries")
    @Operation(operationId = "global-submit-entry",
        summary = "Подать одобренное растение в очередь следующего отбора (U)")
    public Mono<ResponseEntity<GlobalEntryResponse>> submit(
            @Valid @RequestBody SubmitGlobalEntryRequest request) {
        return actors.currentActor().flatMap(actor -> Blocking.call(() -> {
                SubmitGlobalEntryUseCase.GlobalEntryView view = submitEntry.submit(actor,
                    request.plantId());
                return ResponseEntity
                    .created(URI.create("/api/v1/global/entries/" + view.id()))
                    .body(GlobalEntryResponse.from(view));
            }));
    }

    @GetMapping("/me/global-entry")
    @Operation(operationId = "global-get-my-entry",
        summary = "Текущее активное глобальное участие или 404 (U)")
    public Mono<ResponseEntity<GlobalEntryResponse>> myEntry() {
        return actors.currentActor().flatMap(actor -> Blocking.call(() ->
                myEntry.findActive(actor)
                    .map(view -> ResponseEntity.ok(GlobalEntryResponse.from(view)))
                    .orElseGet(() -> ResponseEntity.notFound().build())));
    }

    @DeleteMapping("/global/entries/{entryId}")
    @Operation(operationId = "global-withdraw-entry",
        summary = "Снять заявку из очереди до включения в окно (владелец)")
    public Mono<ResponseEntity<Void>> withdraw(@PathVariable UUID entryId) {
        return actors.currentActor()
            .flatMap(actor -> Blocking.call(() -> {
                withdrawEntry.withdraw(actor, entryId);
                return null;
            }))
            .map(v -> ResponseEntity.noContent().build());
    }

    @GetMapping("/global/clusters")
    @Operation(operationId = "global-list-clusters",
        summary = "Кластеры текущей эпохи отбора (публично, X-Total-Count)")
    public Mono<ResponseEntity<List<GlobalClusterResponse>>> clusters(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Blocking.call(() -> {
            ListGlobalClustersUseCase.ClusterListResult result =
                listClusters.list(PaginationParams.of(page, size));
            return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.total()))
                .body(result.items().stream()
                    .map(cluster -> new GlobalClusterResponse(cluster.clusterId(),
                        cluster.clusterKey(), cluster.windowId(), cluster.memberCount(),
                        cluster.closesAt()))
                    .toList());
        });
    }

    @GetMapping("/global/leaderboard")
    @Operation(operationId = "global-get-final-leaderboard",
        summary = "Рейтинг текущего финального окна (публично, scope=FINAL)")
    public Mono<GlobalLeaderboardResponse> finalLeaderboard(
            @RequestParam(defaultValue = "FINAL") String scope,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Blocking.call(() -> {
            if (!"FINAL".equals(scope)) {
                throw new UnknownGlobalScopeException(
                    "Неизвестный scope: " + scope + " (допустимо: FINAL)");
            }
            return toResponse(leaderboard.finalLeaderboard(PaginationParams.of(page, size)));
        });
    }

    @GetMapping("/global/clusters/{clusterId}/leaderboard")
    @Operation(operationId = "global-get-cluster-leaderboard",
        summary = "Отбор конкретного кластера текущей эпохи (публично)")
    public Mono<GlobalLeaderboardResponse> clusterLeaderboard(@PathVariable UUID clusterId,
                                                                @RequestParam(required = false) Integer page,
                                                                @RequestParam(required = false) Integer size) {
        return Blocking.call(() -> toResponse(leaderboard.clusterLeaderboard(clusterId,
            PaginationParams.of(page, size))));
    }

    private GlobalLeaderboardResponse toResponse(
            GetGlobalLeaderboardUseCase.GlobalLeaderboard board) {
        return new GlobalLeaderboardResponse(board.scope(), board.windowId(), board.closesAt(),
            board.asOf(), board.items().stream()
                .map(item -> new GlobalLeaderboardResponse.Item(item.entryId(), item.userId(),
                    item.score()))
                .toList());
    }
}
