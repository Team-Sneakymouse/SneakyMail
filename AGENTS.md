# Agent instructions

<!-- BEGIN TEAM-POLICY MANAGED BOOTSTRAP -->
This project follows the Team-Sneakymouse development policy, kept in a sibling checkout that `.team-policy/pointer.json` names.

The policy root is the first `local_candidates` path in the pointer, resolved from this project's root, that exists. If none exists, clone the pointer's `remote` into the first candidate with the developer's Git credentials and say so. Before implementation work (not for read-only investigation), run `git fetch` in the checkout, then fast-forward it to `origin/<track_branch>` if it is behind and clean; if the fetch fails or local changes block the fast-forward, report it and ask.

Before implementing, read `<policy-root>/README.md`. It is the policy index and says which documents apply to which kind of change. If the policy root can't be resolved or read, stop and report that instead of working from memory or another copy.

Build commands, architecture facts and recorded local exceptions in this file are authoritative for this repository. Where this file and the policy require incompatible actions, say so before expanding the work. Running checks and writing tests are separate decisions: a source edit does not by itself require a new test.
<!-- END TEAM-POLICY MANAGED BOOTSTRAP -->

Mail lives in PostgreSQL (`sneakymail.mail`, through SneakyDB). Before changing the table, its functions, the cache,
claiming, the Java API or the PocketBase import, read `docs/mail.md`. The names and signatures in
`src/main/resources/db/migrations/V001__mail.sql` are a contract with other repositories (Mother-Mouse-Discord, n8n).

| Task | Command |
|------|---------|
| Build, unit tests, API jar checks | `./gradlew check` |
| V001 and the store against PostgreSQL | `SNEAKYMAIL_TEST_PG_URL=jdbc:postgresql://...  ./gradlew postgresTest` (throwaway database, e.g. `postgres:16` in Docker; see `docs/mail.md`) |
| Import script tests | `python3 -m unittest discover -s tools`; add `SNEAKYMAIL_TEST_PG_DSN=<conninfo>` and psycopg to also test its writes |
