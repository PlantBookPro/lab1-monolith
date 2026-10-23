-- Контекст tournaments: закрытые турниры, приглашения, участия, теги
-- (разделы 7, 11). Enum → VARCHAR + CHECK; маппинг явный (без EnumType.STRING).
-- version (optimistic locking) у конкурентных tournament/invitation.
-- Связи внутри схемы-владельца: FK invitation/entry → tournament,
-- join table tournament_tag (PK по двум FK, раздел 11).
CREATE TABLE tournament (
    id                       UUID PRIMARY KEY,
    creator_id               UUID          NOT NULL,
    name                     VARCHAR(100)  NOT NULL,
    description              VARCHAR(1000),
    type                     VARCHAR(10)   NOT NULL CHECK (type IN ('PRIVATE')),
    status                   VARCHAR(20)   NOT NULL CHECK (status IN ('DRAFT', 'REGISTRATION_OPEN', 'RUNNING', 'FINISHED', 'CANCELLED')),
    algorithm                VARCHAR(20)   NOT NULL CHECK (algorithm IN ('ROUND_ELIMINATION')),
    registration_deadline    TIMESTAMPTZ   NOT NULL,
    round_duration_seconds   BIGINT        NOT NULL CHECK (round_duration_seconds > 0),
    elimination_fraction     DOUBLE PRECISION NOT NULL CHECK (elimination_fraction > 0 AND elimination_fraction < 1),
    min_participants         INTEGER       NOT NULL CHECK (min_participants >= 2),
    cancel_reason            VARCHAR(30)   CHECK (cancel_reason IS NULL OR cancel_reason IN ('INSUFFICIENT_PARTICIPANTS')),
    created_at               TIMESTAMPTZ   NOT NULL,
    version                  BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX tournament_status_deadline_idx ON tournament (status, registration_deadline);
CREATE INDEX tournament_creator_idx ON tournament (creator_id);

CREATE TABLE invitation (
    id                 UUID PRIMARY KEY,
    tournament_id      UUID         NOT NULL REFERENCES tournament (id),
    user_id            UUID         NOT NULL,
    invited_by         UUID         NOT NULL,
    status             VARCHAR(30)  NOT NULL CHECK (status IN ('INVITED', 'ACCEPTED_PENDING_MODERATION', 'READY', 'DECLINED', 'REVOKED', 'EXPIRED')),
    invited_at         TIMESTAMPTZ  NOT NULL,
    responded_at       TIMESTAMPTZ,
    submitted_plant_id UUID,
    reservation_id     UUID,
    submission_key     UUID,
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT invitation_pair_uidx UNIQUE (tournament_id, user_id)
);
CREATE INDEX invitation_user_status_idx ON invitation (user_id, status);
CREATE INDEX invitation_tournament_status_idx ON invitation (tournament_id, status);
CREATE INDEX invitation_plant_status_idx ON invitation (submitted_plant_id, status);

CREATE TABLE tournament_entry (
    id             UUID PRIMARY KEY,
    tournament_id  UUID         NOT NULL REFERENCES tournament (id),
    user_id        UUID         NOT NULL,
    plant_id       UUID         NOT NULL,
    reservation_id UUID         NOT NULL,
    status         VARCHAR(10)  NOT NULL CHECK (status IN ('ACTIVE', 'ELIMINATED', 'WINNER')),
    joined_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT tournament_entry_pair_uidx UNIQUE (tournament_id, user_id)
);

CREATE TABLE tag (
    id         UUID PRIMARY KEY,
    name       VARCHAR(50) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE tournament_tag (
    tournament_id UUID NOT NULL REFERENCES tournament (id),
    tag_id        UUID NOT NULL REFERENCES tag (id),
    PRIMARY KEY (tournament_id, tag_id)
);
