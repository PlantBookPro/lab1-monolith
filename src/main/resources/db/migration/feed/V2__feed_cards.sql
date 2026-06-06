-- Контекст feed: проекция карточек ленты (ADR-002, разделы 9, 11).
-- Обновляется опубликованными событиями tournaments (VotingWindowOpened/
-- Closed), обогащается данными plants/identity на момент открытия окна.
-- created_at — граница snapshotCutoff курсора: новые участники появляются
-- после обновления ленты. title/display_name — снимок (VARCHAR как у
-- владельцев данных: plants.plant.title, identity.app_user.display_name).
-- Без FK на схемы владельцев: контексты выделяются в сервисы (раздел 10.4).
CREATE TABLE feed_card (
    id                 UUID         PRIMARY KEY,
    window_id          UUID         NOT NULL,
    tournament_id      UUID         NOT NULL,
    scope              VARCHAR(13)  NOT NULL CHECK (scope IN ('PRIVATE', 'QUALIFICATION', 'FINAL')),
    cluster_id         UUID,
    entry_id           UUID         NOT NULL,
    user_id            UUID         NOT NULL,
    plant_id           UUID         NOT NULL,
    asset_id           UUID         NOT NULL,
    title              VARCHAR(100) NOT NULL,
    owner_display_name VARCHAR(100) NOT NULL,
    joined_at          TIMESTAMPTZ  NOT NULL,
    closes_at          TIMESTAMPTZ  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT feed_card_pair_uidx UNIQUE (window_id, entry_id)
);
CREATE INDEX feed_card_closes_idx ON feed_card (closes_at);
CREATE INDEX feed_card_created_idx ON feed_card (created_at);
