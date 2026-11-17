-- Operator-owned schema only. Table DDL is applied by SneakyDB at server startup
-- (src/resources/db/migrations/V001__command_mail_journal.sql); see docs/specs/sneakydb-migrations.md.
-- Execute as database owner BEFORE enabling durable mail with SneakyDB.
CREATE SCHEMA sneakymail;
-- Legacy hand-applied databases: grant USAGE on this schema and SELECT/INSERT/UPDATE on
-- sneakymail.command_requests to the SneakyDB role if objects were created under another owner.
-- On migration-only provisioning the application role owns objects and needs no extra grants.
