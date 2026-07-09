CREATE TABLE feature_toggles (
    id         UUID                        NOT NULL,
    name       VARCHAR(100)                NOT NULL,
    percentage INT                         NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_feature_toggles            PRIMARY KEY (id),
    CONSTRAINT uq_feature_toggles_name       UNIQUE (name),
    CONSTRAINT ck_feature_toggles_percentage CHECK (percentage BETWEEN 0 AND 100)
);

CREATE TABLE feature_toggle_org_overrides (
    id              UUID                        NOT NULL,
    toggle_id       UUID                        NOT NULL,
    organization_id UUID                        NOT NULL,
    percentage      INT                         NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_feature_toggle_org_overrides   PRIMARY KEY (id),
    CONSTRAINT fk_ft_org_overrides_toggle        FOREIGN KEY (toggle_id) REFERENCES feature_toggles (id) ON DELETE CASCADE,
    CONSTRAINT fk_ft_org_overrides_organization  FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT uq_ft_org_overrides_toggle_org    UNIQUE (toggle_id, organization_id),
    CONSTRAINT ck_ft_org_overrides_percentage    CHECK (percentage BETWEEN 0 AND 100)
);

-- Supports listing a toggle's overrides without a full table scan as overrides grow.
CREATE INDEX idx_ft_org_overrides_toggle ON feature_toggle_org_overrides (toggle_id);
