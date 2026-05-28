package com.plantarena.identity.adapter.in.web;

import com.plantarena.identity.application.UserResult;
import com.plantarena.identity.application.port.in.UserAdministrationUseCase;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Служебные ручки пользователей (раздел 13). Контроллер обращается только
 * к входным портам application (правило 10.2.7).
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "identity")
public class UserController {

    private final UserAdministrationUseCase administration;
    private final CurrentActorProvider currentActorProvider;

    public UserController(UserAdministrationUseCase administration,
                          CurrentActorProvider currentActorProvider) {
        this.administration = administration;
        this.currentActorProvider = currentActorProvider;
    }

    @PostMapping
    @Operation(operationId = "identity-create-user",
        summary = "Создать пользователя (только роль USER; модератор/админ)")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        UserResult created = administration.create(actor, new UserAdministrationUseCase.CreateUserCommand(
            request.email(), request.password(), request.displayName()));
        return ResponseEntity
            .created(URI.create("/api/v1/users/" + created.id()))
            .body(UserResponse.from(created));
    }

    @GetMapping
    @Operation(operationId = "identity-list-users",
        summary = "Список пользователей (модератор/админ), X-Total-Count")
    public ResponseEntity<List<UserResponse>> list(@RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        UserAdministrationUseCase.UserListResult result =
            administration.list(actor, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(result.items().stream().map(UserResponse::from).toList());
    }

    @GetMapping("/{id}")
    @Operation(operationId = "identity-get-user",
        summary = "Профиль пользователя (сам пользователь, модератор/админ)")
    public UserResponse get(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return UserResponse.from(administration.get(actor, id));
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "identity-update-user",
        summary = "Изменить displayName (владелец/админ); roles/status менять нельзя")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        return UserResponse.from(administration.updateDisplayName(actor, id, request.displayName()));
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "identity-deactivate-user",
        summary = "Деактивировать пользователя (админ)")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        administration.deactivate(actor, id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/roles/moderator")
    @Operation(operationId = "identity-grant-moderator",
        summary = "Назначить роль MODERATOR (админ, идемпотентно)")
    public UserResponse grantModerator(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return UserResponse.from(administration.grantModerator(actor, id));
    }

    @DeleteMapping("/{id}/roles/moderator")
    @Operation(operationId = "identity-revoke-moderator",
        summary = "Снять роль MODERATOR, не удаляя USER (админ, идемпотентно)")
    public UserResponse revokeModerator(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return UserResponse.from(administration.revokeModerator(actor, id));
    }
}
