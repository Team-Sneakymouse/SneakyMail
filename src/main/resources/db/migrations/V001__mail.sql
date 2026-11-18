-- Mail for players: one row per mail, its rewards handed out once when the recipient claims it in game.
-- The operator creates the schema sneakymail; this script only creates objects in it. The old journal table
-- sneakymail.command_requests (from before SneakyMail used migrations) is left alone; a later script drops it.
-- These names and signatures are a contract: Mother-Mouse-Discord, n8n workflows and hand-written SQL call them.

-- True when p_rewards is a JSON array whose every element is exactly one of
--   {"type":"command","command":<non-empty string>}
--   {"type":"item","item":<non-empty string>,"amount":<integer 1..2304>}
-- with no other keys. [] is valid. People insert mail by hand, so the table refuses anything else.
create function sneakymail.rewards_valid(p_rewards jsonb) returns boolean
language plpgsql immutable
set search_path = pg_catalog, pg_temp
as $$
declare
    reward jsonb;
    reward_keys text[];
begin
    if p_rewards is null or jsonb_typeof(p_rewards) <> 'array' then
        return false;
    end if;
    for reward in select value from jsonb_array_elements(p_rewards) loop
        if jsonb_typeof(reward) <> 'object' then
            return false;
        end if;
        select array_agg(key order by key) into reward_keys from jsonb_object_keys(reward) as key;
        if reward_keys = array['command', 'type'] and reward->>'type' = 'command' then
            if jsonb_typeof(reward->'command') <> 'string' or reward->>'command' = '' then
                return false;
            end if;
        elsif reward_keys = array['amount', 'item', 'type'] and reward->>'type' = 'item' then
            if jsonb_typeof(reward->'item') <> 'string' or reward->>'item' = ''
                or jsonb_typeof(reward->'amount') <> 'number'
                or reward->>'amount' !~ '^[0-9]{1,4}$'
                or (reward->>'amount')::integer not between 1 and 2304 then
                return false;
            end if;
        else
            return false;
        end if;
    end loop;
    return true;
end
$$;

create table sneakymail.mail (
    id             bigint generated always as identity primary key,
    -- Set by senders that retry: sneakymail.send() returns the existing mail for a key it has seen.
    source_key     text unique check (length(source_key) between 1 and 512),
    recipient_uuid uuid not null,
    sender_name    text not null,
    note           text not null default '',
    rewards        jsonb not null default '[]' check (sneakymail.rewards_valid(rewards)),
    created_at     timestamptz not null default now(),
    -- sneakymail.delete_expired() deletes unclaimed mail after this. The plugin ignores it.
    expires_at     timestamptz,
    -- Null until the recipient claims the mail in game.
    claimed_at     timestamptz
);

create index mail_unclaimed_by_recipient on sneakymail.mail (recipient_uuid) where claimed_at is null;
create index mail_unclaimed_by_expiry on sneakymail.mail (expires_at)
    where claimed_at is null and expires_at is not null;

-- Sends a mail and returns its id. With a source key it is idempotent: a second call with the same key and the same
-- recipient, sender, note, rewards and expiry returns the first mail's id; different content raises SQLSTATE 23505.
-- A null note becomes ''. Runs as its owner, so callers need only EXECUTE (granted by an operator).
create function sneakymail.send(
    p_source_key  text,
    p_recipient   uuid,
    p_sender_name text,
    p_note        text,
    p_rewards     jsonb,
    p_expires_at  timestamptz default null
) returns bigint
language plpgsql security definer
set search_path = pg_catalog, pg_temp
as $$
declare
    v_note text := coalesce(p_note, '');
    v_id bigint;
    existing record;
begin
    if p_recipient is null or p_sender_name is null or p_rewards is null then
        raise exception 'sneakymail.send: recipient, sender name and rewards must not be null'
            using errcode = 'null_value_not_allowed';
    end if;
    if p_source_key is null then
        insert into sneakymail.mail (recipient_uuid, sender_name, note, rewards, expires_at)
        values (p_recipient, p_sender_name, v_note, p_rewards, p_expires_at)
        returning id into v_id;
        return v_id;
    end if;
    -- A concurrent call with the same key waits in the insert until the other transaction ends, then reads its row.
    -- The loop covers a row deleted between the two statements; under REPEATABLE READ the row may stay invisible.
    for attempt in 1..3 loop
        insert into sneakymail.mail (source_key, recipient_uuid, sender_name, note, rewards, expires_at)
        values (p_source_key, p_recipient, p_sender_name, v_note, p_rewards, p_expires_at)
        on conflict (source_key) do nothing
        returning id into v_id;
        if v_id is not null then
            return v_id;
        end if;
        select id, recipient_uuid, sender_name, note, rewards, expires_at into existing
        from sneakymail.mail where source_key = p_source_key;
        if found then
            if existing.recipient_uuid is not distinct from p_recipient
                and existing.sender_name is not distinct from p_sender_name
                and existing.note is not distinct from v_note
                and existing.rewards is not distinct from p_rewards
                and existing.expires_at is not distinct from p_expires_at then
                return existing.id;
            end if;
            raise exception 'sneakymail.send: source key was reused with different content (mail %)', existing.id
                using errcode = 'unique_violation';
        end if;
    end loop;
    raise exception 'sneakymail.send: the mail for this source key could not be read; retry'
        using errcode = 'serialization_failure';
end
$$;

revoke execute on function sneakymail.send(text, uuid, text, text, jsonb, timestamptz) from public;

-- Deletes unclaimed mail whose expires_at has passed and returns how many. pg_cron calls it (operator-scheduled).
create function sneakymail.delete_expired() returns integer
language sql
set search_path = pg_catalog, pg_temp
as $$
    with deleted as (
        delete from sneakymail.mail where claimed_at is null and expires_at < now() returning 1
    )
    select count(*)::integer from deleted;
$$;

-- Notifications on channel sneakymail, so servers show mail inserted anywhere (DBeaver included) without a restart:
--   {"event":"new","id":<id>,"recipient":"<uuid>"}      after an insert
--   {"event":"claimed","id":<id>,"recipient":"<uuid>"}  when claimed_at goes from null to a value
--   {"event":"deleted","id":<id>,"recipient":"<uuid>"}  after a delete
create function sneakymail.mail_notify() returns trigger
language plpgsql
set search_path = pg_catalog, pg_temp
as $$
declare
    row_event text;
    changed sneakymail.mail;
begin
    if tg_op = 'INSERT' then
        row_event := 'new';
        changed := new;
    elsif tg_op = 'UPDATE' then
        if old.claimed_at is not null or new.claimed_at is null then
            return null;
        end if;
        row_event := 'claimed';
        changed := new;
    else
        row_event := 'deleted';
        changed := old;
    end if;
    perform pg_notify('sneakymail',
        format('{"event":"%s","id":%s,"recipient":"%s"}', row_event, changed.id, changed.recipient_uuid));
    return null;
end
$$;

create trigger mail_notify after insert or update or delete on sneakymail.mail
    for each row execute function sneakymail.mail_notify();
