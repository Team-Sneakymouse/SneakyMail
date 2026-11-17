# Spec: SneakyDB migrations for SneakyMail

## Goal

Adopt team [database migrations](https://github.com/Team-Sneakymouse/team-development-policy/blob/main/policy/database-migrations.md) for the `sneakymail` schema and `command_requests` journal.

## Non-goals

- Do not create or drop the `sneakymail` schema in scripts (operator creates it).
- Do not change admission, delivery, or recovery semantics.

## Plugin id

`migrate` uses `sneakymail` (`SneakyMail.IDENTIFIER`).

## Scripts

| Script | Purpose |
|--------|---------|
| `V001__command_mail_journal.sql` | `command_requests` table, pending index, immutability trigger/function from former [command-mail-journal.sql](../command-mail-journal.sql) without `CREATE SCHEMA`, transaction wrappers, or `GRANT`. |

## Runtime

When SneakyDB is present and the durable mail path enables, `MailRuntime` calls `SneakyDBProvider.getApi().migrate(IDENTIFIER, DurableMailService::class.java)` and does not install the durable API provider or start the delivery worker until that future completes. On failure the durable path stays inert (legacy PocketBase mail unaffected); no exception is thrown from plugin enable.

## Tests

SQL integration tests create schema `sneakymail`, apply `src/resources/db/migrations/V001__command_mail_journal.sql`, then exercise production `SUBMIT`/`CLAIM`/… SQL.

## Operator adoption

1. Ensure schema `sneakymail` exists and the SneakyDB role has `USAGE` and `CREATE` on it (see [SneakyDB migrations](https://github.com/Team-Sneakymouse/SneakyDB#migrations)).
2. If Live already matches the former operator journal DDL, record version 1 in `sneakydb.schema_migrations` before deploying a jar that calls `migrate` (see SneakyDB **Existing tables**).
3. Deploy on an `apply` server first; `verify` servers need a matching applied version before they start.