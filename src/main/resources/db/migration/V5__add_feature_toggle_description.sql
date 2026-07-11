-- Feature-toggle descriptions are code-owned (the Feature enum) and synchronized at startup.
-- Existing rows (only ever present in a persistent DB; production H2 is in-memory and rebuilt each
-- boot) get an empty default that the startup synchronizer immediately overwrites from the enum.
ALTER TABLE feature_toggles
    ADD COLUMN description VARCHAR(500) NOT NULL DEFAULT '';
