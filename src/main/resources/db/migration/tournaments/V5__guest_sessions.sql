-- Контекст tournaments: гостевые сессии (разделы 9, 11). Хранится только
-- хэш токена (SHA-256 hex, 64 символа) и срок действия; токен выдаётся
-- клиенту один раз и нигде не сохраняется. Агрегат неизменяем — без version.
CREATE TABLE guest_session (
    id         UUID         PRIMARY KEY,
    token_hash VARCHAR(64)  NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT guest_session_interval_chk CHECK (created_at < expires_at)
);
CREATE INDEX guest_session_expires_idx ON guest_session (expires_at);
