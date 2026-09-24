-- Контекст identity: пользователи, справочник ролей, связь (раздел 11 требований).
-- Enum → VARCHAR + CHECK; version — optimistic locking; email нормализуется доменом.
CREATE TABLE app_user (
    id               UUID PRIMARY KEY,
    email_normalized VARCHAR(320)  NOT NULL UNIQUE,
    display_name     VARCHAR(100)  NOT NULL,
    password_hash    VARCHAR(100)  NOT NULL,
    status           VARCHAR(20)   NOT NULL CHECK (status IN ('ACTIVE', 'DEACTIVATED')),
    latitude         DOUBLE PRECISION CHECK (latitude BETWEEN -90 AND 90),
    longitude        DOUBLE PRECISION CHECK (longitude BETWEEN -180 AND 180),
    version          BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE role (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE CHECK (code IN ('USER', 'MODERATOR', 'ADMIN'))
);

CREATE TABLE user_role (
    user_id UUID   NOT NULL REFERENCES app_user (id),
    role_id BIGINT NOT NULL REFERENCES role (id),
    PRIMARY KEY (user_id, role_id)
);

INSERT INTO role (code) VALUES ('USER'), ('MODERATOR'), ('ADMIN');
