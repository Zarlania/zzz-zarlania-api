CREATE TABLE password_credentials (
    id            UUID                        NOT NULL,
    user_id       UUID                        NOT NULL,
    password_hash VARCHAR(255)                NOT NULL,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_password_credentials      PRIMARY KEY (id),
    CONSTRAINT uq_password_credentials_user UNIQUE (user_id),
    CONSTRAINT fk_password_credentials_user FOREIGN KEY (user_id) REFERENCES users (id)
);
