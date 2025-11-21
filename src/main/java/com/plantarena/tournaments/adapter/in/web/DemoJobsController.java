package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import com.plantarena.tournaments.application.TournamentsAccessPolicy;
import com.plantarena.tournaments.application.port.in.AdvanceGlobalCompetitionUseCase;
import com.plantarena.tournaments.application.port.in.CloseVotingWindowUseCase;
import com.plantarena.tournaments.application.port.in.StartTournamentUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Диагностика (раздел 13): обработка наступивших дедлайнов через тот же use
 * case, что scheduler и ручка старта — без обхода правил. Только профили
 * dev/test (в обычной конфигурации бин отсутствует); доступ — M/A. Время —
 * только Clock (публичной ручки времени нет); отдельный Swagger tag.
 */
@RestController
@RequestMapping("/api/v1/internal/demo/jobs")
@Profile({"dev", "test"})
@Tag(name = "demo")
public class DemoJobsController {

    private final StartTournamentUseCase startTournament;
    private final CloseVotingWindowUseCase closeVotingWindow;
    private final AdvanceGlobalCompetitionUseCase advanceGlobalCompetition;
    private final TournamentsAccessPolicy accessPolicy;
    private final CurrentActorProvider currentActorProvider;
    private final Clock clock;

    public DemoJobsController(StartTournamentUseCase startTournament,
                              CloseVotingWindowUseCase closeVotingWindow,
                              AdvanceGlobalCompetitionUseCase advanceGlobalCompetition,
                              TournamentsAccessPolicy accessPolicy,
                              CurrentActorProvider currentActorProvider, Clock clock) {
        this.startTournament = startTournament;
        this.closeVotingWindow = closeVotingWindow;
        this.advanceGlobalCompetition = advanceGlobalCompetition;
        this.accessPolicy = accessPolicy;
        this.currentActorProvider = currentActorProvider;
        this.clock = clock;
    }

    @PostMapping("/run-due")
    @Operation(operationId = "demo-run-due-jobs",
        summary = "Обработать наступившие дедлайны турниров и окон (dev/test, M/A)")
    public Map<String, Integer> runDue() {
        CurrentActor actor = currentActorProvider.currentActor();
        accessPolicy.requireModeratorOrAdmin(actor);
        AdvanceGlobalCompetitionUseCase.AdvanceReport report =
            advanceGlobalCompetition.advance(clock.instant());
        return Map.of(
            "processed", startTournament.startDue(clock.instant(), 10),
            "closedWindows", closeVotingWindow.closeDue(clock.instant(), 10),
            "globalQualificationClosed", report.qualificationClosed(),
            "globalFinalClosed", report.finalClosed(),
            "globalFinalsOpened", report.finalsOpened(),
            "globalEpochsOpened", report.epochsOpened());
    }
}
