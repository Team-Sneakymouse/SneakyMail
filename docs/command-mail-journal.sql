-- Operator-owned installation. Execute as database owner BEFORE enabling the new plugin.
-- No automatic DDL; never point the plugin at an empty schema and assume acceptance succeeded.
CREATE SCHEMA sneakymail;
CREATE TABLE sneakymail.command_requests (
 source_key text PRIMARY KEY CHECK (length(source_key) BETWEEN 1 AND 512),
 request_id text NOT NULL UNIQUE CHECK (request_id ~ '^[a-z0-9]{15}$'),
 payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
 created_at timestamptz NOT NULL DEFAULT now(),
 attempted_at timestamptz,
 attempt_token text,
 next_attempt_at timestamptz,
 quarantined_at timestamptz,
 last_checked timestamptz,
 lease_token text,
 lease_until timestamptz,
 confirmed_at timestamptz
);
CREATE INDEX command_requests_pending ON sneakymail.command_requests
 ((attempted_at IS NOT NULL), last_checked NULLS FIRST, created_at, request_id)
 WHERE confirmed_at IS NULL;
-- Protect permanent admission identity and receipts even from accidental runtime UPDATEs.
CREATE FUNCTION sneakymail.protect_command_request() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.source_key IS DISTINCT FROM OLD.source_key
    OR NEW.request_id IS DISTINCT FROM OLD.request_id
    OR NEW.payload IS DISTINCT FROM OLD.payload
    OR NEW.created_at IS DISTINCT FROM OLD.created_at
    OR (OLD.confirmed_at IS NOT NULL AND NEW.confirmed_at IS DISTINCT FROM OLD.confirmed_at) THEN
  RAISE EXCEPTION 'Immutable mail admission or receipt';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER command_requests_immutable BEFORE UPDATE ON sneakymail.command_requests
 FOR EACH ROW EXECUTE FUNCTION sneakymail.protect_command_request();
-- Replace sneakymail_runtime with the actual SneakyDB configured database role:
-- GRANT USAGE ON SCHEMA sneakymail TO sneakymail_runtime;
-- GRANT SELECT, INSERT, UPDATE ON sneakymail.command_requests TO sneakymail_runtime;
-- Do NOT grant DELETE/TRUNCATE or prune this table: keys and confirmed receipts are permanent.
