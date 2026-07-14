# Reference Doc Tags

Registry of all tags used across reference docs (separate from the ADR registry).
**Reuse an existing tag before creating a new one.** Adding a tag to a doc requires adding
it here in the same change (`./scripts/ref add-tag <tag> --description "..."`).

Tags are kept in **alphabetical order** — both here and in each doc's `tags` list.
`./scripts/ref add-tag` inserts new rows in order, and `./scripts/ref check` fails on any
registry or doc whose tags are not sorted.

| Tag | Description |
| --- | --- |
| architecture | System structure, boundaries, and cross-domain conventions |
| domain-model | Domain entities, relationships, and invariants |
| security | Security model and controls (authN/authZ, secrets, exposure) |
| testing | Test strategy, layering, and conventions |
