-- Operator-owned installation. Execute as database owner BEFORE enabling the new plugin.
-- No automatic DDL; never point the plugin at an empty schema and assume acceptance succeeded.
CREATE SCHEMA sneakymail;
CREATE TABLE sneakymail.command_requests (
 source_key text PRIMARY KEY CHECK (length(source_key) BETWEEN 1 AND 512),
 request_id text NOT NULL UNIQUE CHECK (request_id ~ '^[a-z0-9]{15}$'),
 payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
 created_at timestamptz NOT NULL DEFAULT now(),
 attempted_at timestamptz,
 last_checked timestamptz,
 lease_token text,
 lease_until timestamptz,
 confirmed_at timestamptz
);
CREATE INDEX command_requests_pending ON sneakymail.command_requests
 ((attempted_at IS NOT NULL), last_checked, created_at, request_id)
 WHERE confirmed_at IS NULL;
-- Replace sneakymail_runtime with the actual SneakyDB configured database role:
-- GRANT USAGE ON SCHEMA sneakymail TO sneakymail_runtime;
-- GRANT SELECT, INSERT, UPDATE ON sneakymail.command_requests TO sneakymail_runtime;
-- Do NOT grant DELETE/TRUNCATE or prune this table: keys and confirmed receipts are permanent.
