package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.TagsUseCase;
import com.plantarena.tournaments.application.support.InMemoryTagRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Справочник тегов: CRUD и права (раздел 13)")
class TagsServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryTagRepository tags = new InMemoryTagRepository(tournaments);
    private final TournamentRepository tournamentRepository = tournaments;
    private final TagsUseCase service = new TagsService(tags, tournamentRepository,
        new TournamentsAccessPolicy(), Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID adminId = UUID.randomUUID();
    private final CurrentActor admin =
        CurrentActor.identified(adminId, Set.of(AppRole.USER, AppRole.ADMIN));
    private final CurrentActor moderator =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER, AppRole.MODERATOR));
    private final CurrentActor plainUser =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER));

    @Test
    @DisplayName("создание: модератор и админ; обычный пользователь — 403; дубль имени — 409")
    void создание_и_права() {
        assertThat(service.create(moderator, "  Комнатные  ").name()).isEqualTo("Комнатные");
        assertThat(service.create(admin, "Суккуленты").name()).isEqualTo("Суккуленты");

        assertThatThrownBy(() -> service.create(plainUser, "Нельзя"))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(admin, "Комнатные"))
            .isInstanceOf(TagAlreadyExistsException.class);
    }

    @Test
    @DisplayName("переименование: только админ; имя другого тега занято")
    void переименование() {
        UUID tagId = service.create(admin, "Комнатные").id();
        service.create(admin, "Суккуленты");

        assertThatThrownBy(() -> service.rename(moderator, tagId, "Кактусы"))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(service.rename(admin, tagId, "Кактусы").name()).isEqualTo("Кактусы");
        assertThatThrownBy(() -> service.rename(admin, tagId, "Суккуленты"))
            .isInstanceOf(TagAlreadyExistsException.class);
        assertThatThrownBy(() -> service.rename(admin, UUID.randomUUID(), "X"))
            .isInstanceOf(TagNotFoundException.class);
    }

    @Test
    @DisplayName("удаление: используемый турниром тег — 409, свободный — удаляется")
    void удаление() {
        UUID usedTag = service.create(admin, "Используемый").id();
        UUID freeTag = service.create(admin, "Свободный").id();
        tournaments.save(Tournament.createDraft(adminId, "Т", null,
            NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2, Set.of(usedTag), NOW));

        assertThatThrownBy(() -> service.delete(admin, usedTag))
            .isInstanceOf(TagInUseException.class);

        service.delete(admin, freeTag);
        assertThatThrownBy(() -> service.get(admin, freeTag))
            .isInstanceOf(TagNotFoundException.class);
    }

    @Test
    @DisplayName("список: пагинация и total")
    void список() {
        service.create(admin, "Один");
        service.create(admin, "Два");
        service.create(admin, "Три");

        TagsUseCase.TagListResult page = service.list(admin, 1, 2);
        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(3);
    }
}
