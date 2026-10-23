package com.plantarena.geo.adapter.in.web;

import com.plantarena.geo.api.ClusterAssignment;
import com.plantarena.geo.application.ClusterAssignmentService;
import com.plantarena.geo.domain.ClusterSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/internal/clusters")
@RequiredArgsConstructor
public class InternalClusterController {

    private final ClusterAssignmentService service;

    @PostMapping("/assignments")
    public Mono<List<ClusterAssignment.AssignedCluster>> assign(@RequestBody AssignmentRequest request) {
        return service.assignClusters(request.epochId(), request.members());
    }

    @GetMapping
    public Flux<ClusterSnapshotResponse> list(@RequestParam UUID epochId) {
        return service.findByEpoch(epochId)
            .map(s -> new ClusterSnapshotResponse(s.id(), s.epochId(), s.clusterKey(),
                s.policyVersion(), s.createdAt(), s.members().size()));
    }

    public record AssignmentRequest(UUID epochId, List<ClusterAssignment.Member> members) {}

    public record ClusterSnapshotResponse(UUID id, UUID epochId, String clusterKey,
                                          String policyVersion, Instant createdAt, int memberCount) {}
}
