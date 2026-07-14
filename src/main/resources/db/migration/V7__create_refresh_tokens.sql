CREATE TABLE refresh_tokens (
    id              UUID                        NOT NULL,
    user_id         UUID                        NOT NULL,
    organization_id UUID                        NOT NULL,
    token_hash      VARCHAR(64)                 NOT NULL,
    family_id       UUID                        NOT NULL,
    issued_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    consumed_at     TIMESTAMP(6) WITH TIME ZONE,
    revoked_at      TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_refresh_tokens      PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_refresh_tokens_org  FOREIGN KEY (organization_id) REFERENCES organizations (id)
);

CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
