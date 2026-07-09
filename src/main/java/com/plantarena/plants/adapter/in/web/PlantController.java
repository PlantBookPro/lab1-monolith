package com.plantarena.plants.adapter.in.web;

import com.plantarena.plants.api.PlantData;
import com.plantarena.plants.application.port.in.ArchivePlantUseCase;
import com.plantarena.plants.application.port.in.GetPlantModerationUseCase;
import com.plantarena.plants.application.port.in.GetPlantUseCase;
import com.plantarena.plants.application.port.in.ListPlantsUseCase;
import com.plantarena.plants.application.port.in.RenamePlantUseCase;
import com.plantarena.plants.application.port.in.SubmitPlantUseCase;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import com.plantarena.shared.web.PaginationParams;
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
 * Ручки растений (раздел 13). Контроллер обращается только к входным портам
 * application (правило 10.2.7); видимость и права решает use case/AccessPolicy.
 */
@RestController
@RequestMapping("/api/v1/plants")
@Tag(name = "plants")
public class PlantController {

    private final SubmitPlantUseCase submitPlant;
    private final ListPlantsUseCase listPlants;
    private final GetPlantUseCase getPlant;
    private final RenamePlantUseCase renamePlant;
    private final ArchivePlantUseCase archivePlant;
    private final GetPlantModerationUseCase getPlantModeration;
    private final CurrentActorProvider currentActorProvider;

    public PlantController(SubmitPlantUseCase submitPlant, ListPlantsUseCase listPlants,
                           GetPlantUseCase getPlant, RenamePlantUseCase renamePlant,
                           ArchivePlantUseCase archivePlant,
                           GetPlantModerationUseCase getPlantModeration,
                           CurrentActorProvider currentActorProvider) {
        this.submitPlant = submitPlant;
        this.listPlants = listPlants;
        this.getPlant = getPlant;
        this.renamePlant = renamePlant;
        this.archivePlant = archivePlant;
        this.getPlantModeration = getPlantModeration;
        this.currentActorProvider = currentActorProvider;
    }

    @PostMapping
    @Operation(operationId = "plants-submit-plant",
        summary = "Подать заявку «это моё растение» на свой файл (USER и выше)")
    public ResponseEntity<PlantResponse> submit(@Valid @RequestBody SubmitPlantRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        PlantData plant = submitPlant.submit(actor,
            new SubmitPlantUseCase.SubmitPlantCommand(request.assetId(), request.title()));
        return ResponseEntity
            .created(URI.create("/api/v1/plants/" + plant.id()))
            .body(PlantResponse.from(plant));
    }

    @GetMapping
    @Operation(operationId = "plants-list-plants",
        summary = "Список растений (свои — все статусы, чужие — только одобренные), X-Total-Count")
    public ResponseEntity<List<PlantResponse>> list(
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        ListPlantsUseCase.PlantListResult result =
            listPlants.list(actor, ownerId, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(result.items().stream().map(PlantResponse::from).toList());
    }

    @GetMapping("/{id}")
    @Operation(operationId = "plants-get-plant",
        summary = "Просмотр растения (владелец/админ всегда; чужие — только одобренные)")
    public PlantResponse get(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return PlantResponse.from(getPlant.get(actor, id));
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "plants-rename-plant",
        summary = "Переименовать растение (только владелец; только title)")
    public PlantResponse rename(@PathVariable UUID id, @Valid @RequestBody RenamePlantRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        return PlantResponse.from(renamePlant.rename(actor, id, request.title()));
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "plants-archive-plant",
        summary = "Архивировать растение (только владелец; вне активного резерва)")
    public ResponseEntity<Void> archive(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        archivePlant.archive(actor, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/moderation")
    @Operation(operationId = "plants-get-moderation",
        summary = "Статус модерации заявки (только владелец)")
    public PlantModerationResponse moderation(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return PlantModerationResponse.from(getPlantModeration.moderation(actor, id));
    }
}
