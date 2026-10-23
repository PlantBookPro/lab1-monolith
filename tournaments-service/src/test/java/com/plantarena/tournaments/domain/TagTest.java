package com.plantarena.tournaments.domain;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Агрегат Tag: справочник тематик, валидация имени")
class TagTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @Test
    @DisplayName("фабрика: имя обрезается, пустое запрещено")
    void фабрика_имя() {
        Tag tag = Tag.create("  Комнатные  ", NOW);
        assertThat(tag.name()).isEqualTo("Комнатные");
        assertThat(tag.createdAt()).isEqualTo(NOW);

        assertThatThrownBy(() -> Tag.create("  ", NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tag.create(null, NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rename: то же правило")
    void rename() {
        Tag tag = Tag.create("Комнатные", NOW);
        tag.rename("Кактусы");
        assertThat(tag.name()).isEqualTo("Кактусы");
        assertThatThrownBy(() -> tag.rename(" "))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
