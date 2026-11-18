#!/usr/bin/env python3
"""One-time import of PocketBase's lom2_mail into sneakymail.mail.

Not part of the plugin. Run it once at the cutover, after a server with this SneakyMail build has created the table
(schema version 1) and before players claim mail in PostgreSQL. It refuses to run when sneakymail.mail holds any row,
so it can't import twice.

    uv run --with "psycopg[binary]" python tools/import_pocketbase.py \\
        --pocketbase-env <file with POCKETBASE_HOST, POCKETBASE_USERNAME, POCKETBASE_PASSWORD> \\
        --pg-env <file with PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD> \\
        --magicspells-data <MagicSpells-Data checkout, branch the server runs> \\
        [--items-file <another plugin's magic-items.yml>]... [--dry-run] \\
        [--accept-lost-journal-keys] [--accept-unconfirmed-journal-rows]

Without the two env files it reads the same variables from the environment. Everything is written in one transaction
that holds an exclusive lock on sneakymail.mail; --dry-run rolls it back after printing what it would have done.
It only reads PocketBase. No secrets are printed. Every inserted row sends a "new" notification on channel sneakymail
when the transaction commits; SneakyMail ignores those for mail that is claimed or whose recipient is offline.

Rules (decided by the project owner, 2026-11-18):
- Every record is imported except unclaimed mail with a `lootbox {player} <time>` command whose time has passed.
  The time is epoch milliseconds, or epoch seconds when it has 11 digits or fewer (older lootbox mail).
- recipient_uuid: the record's recipient_uuid, dashed or 32 hex digits; when it is empty or doesn't parse,
  lower(recipient_name) looked up in sneakyusermeta.accounts. A name with no account or with several is skipped.
- claimed_at is PocketBase's `updated` for records with available = false, else null; created_at is `created`.
- expires_at is the lootbox time of a mail with a lootbox command (the earliest, if several), else null.
- `ms magicitem <name> [amount] {player}` (MagicSpells' syntax: amount before the player) becomes
  {"type":"item","item":<name>,"amount":<amount or 1>}. MagicSpells registers its magic items in SneakyItems under
  the same internal names, so the name stays as it is. Names that no definition in --magicspells-data (loaded the
  way MagicSpells loads them) or an --items-file has are listed; they still convert. Other commands stay commands.
- Keys of a reward that the old plugin ignored (it decoded with ignoreUnknownKeys) are stripped and counted.
- source_key: the record's own source_key when it has one; otherwise, if sneakymail.command_requests exists, the
  source_key of the journal row whose request_id is the record's id. Such a journal-keyed row keeps the admitted
  payload exactly (its command is not converted, expires_at stays null), because lom-phone-app retries an old key
  with the journal's payload and sneakymail.send compares it.

It refuses to commit, in a dry run too (exit code 1), when:
- a journal-keyed row still differs from its journal payload (recipient, sender, note, the single command);
- a journal key ends up on no imported row (record gone, skipped or carrying another key), unless
  --accept-lost-journal-keys accepts that a retry of those keys sends the mail again;
- a journal row was never confirmed, unless --accept-unconfirmed-journal-rows accepts that its mail is not sent again;
- an unclaimed mail has rewards that are still invalid, or a lootbox number that is no time.
Claimed mail with invalid rewards is skipped and listed; claimed mail with a lootbox number that is no time is
imported without expires_at and listed.

Needs Python 3.11+ and psycopg 3. Exit code 0 only when the import (or dry run) completed, read back correctly and
nothing refused it.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

# The db/migrations version whose table this script writes (V001__mail.sql).
SCHEMA_VERSION = 1
PLUGIN_ID = "sneakymail"

DASHED_UUID = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
COMPACT_UUID = re.compile(r"[0-9a-fA-F]{32}")
LOOTBOX = re.compile(r"lootbox\s+\{player\}\s+(\d+)(?:\s.*)?", re.DOTALL)
MAGICITEM = re.compile(r"ms\s+magicitem\s+(\S+)(?:\s+(\d+))?\s+\{player\}")
MAGICITEM_ANY = re.compile(r"ms\s+magicitem\b")
MAX_AMOUNT = 2304
# Below this a lootbox time is in seconds: 10^11 ms is 1973, 10^11 s is the year 5138.
SECONDS_BELOW = 10**11


def parse_time(value: str) -> datetime:
    """PocketBase's '2025-03-17 12:34:56.789Z' as an aware datetime."""
    parsed = datetime.fromisoformat(value.strip().replace(" ", "T").replace("Z", "+00:00"))
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def parse_uuid(value: str | None) -> str | None:
    """A dashed or 32-hex-digit UUID as lower-case dashed text, else None."""
    text = (value or "").strip()
    if COMPACT_UUID.fullmatch(text):
        text = f"{text[:8]}-{text[8:12]}-{text[12:16]}-{text[16:20]}-{text[20:]}"
    return text.lower() if DASHED_UUID.fullmatch(text) else None


def lootbox_expiry(command: str) -> tuple[datetime, bool] | None:
    """The time of a `lootbox {player} <time> ...` command, and whether it was given in seconds."""
    match = LOOTBOX.fullmatch(command.strip())
    if not match:
        return None
    number = int(match.group(1))
    seconds = number < SECONDS_BELOW
    return datetime.fromtimestamp(number if seconds else number / 1000, tz=timezone.utc), seconds


def convert_command(command: str) -> dict:
    """A command reward, or the item reward that a convertible `ms magicitem` command becomes."""
    match = MAGICITEM.fullmatch(command.strip())
    if match:
        amount = int(match.group(2)) if match.group(2) else 1
        if 1 <= amount <= MAX_AMOUNT:
            return {"type": "item", "item": match.group(1), "amount": amount}
    return {"type": "command", "command": command}


def reward_valid(reward) -> bool:
    """The same rule as sneakymail.rewards_valid, for one element."""
    if not isinstance(reward, dict):
        return False
    if set(reward) == {"type", "command"} and reward["type"] == "command":
        return isinstance(reward["command"], str) and reward["command"] != ""
    if set(reward) == {"type", "item", "amount"} and reward["type"] == "item":
        amount = reward["amount"]
        return (isinstance(reward["item"], str) and reward["item"] != "" and isinstance(amount, int)
                and not isinstance(amount, bool) and 1 <= amount <= MAX_AMOUNT)
    return False


# --- magic item definitions --------------------------------------------------------------------------------------------

KEY = re.compile(r"""(?:"([^"]+)"|'([^']+)'|([^\s#"'][^:#]*?))\s*:(?:\s|$)""")


def top_level_keys(text: str) -> list[str]:
    return [key for level, key in _keys(text) if level == 0]


def _keys(text: str):
    """(indent, key) of every line that starts a mapping key; comments and blank lines skipped."""
    for raw in text.splitlines():
        line = raw.rstrip()
        stripped = line.lstrip(" ")
        if not stripped or stripped.startswith("#") or stripped.startswith("- "):
            continue
        match = KEY.match(stripped)
        if match:
            yield len(line) - len(stripped), next(group for group in match.groups() if group)


def magic_item_names(text: str, flat: bool = False) -> set[str]:
    """Keys of a top-level `magic-items:` map in a YAML file, read line by line (no YAML library).

    With flat, a file without `magic-items:` has its items as top-level keys (SneakyItems' registerMagicItemsFile).
    Block scalars and nested maps sit deeper than the item keys, so only the first level under `magic-items:` counts.
    """
    names: set[str] = set()
    inside = found = False
    item_indent = None
    for level, key in _keys(text):
        if level == 0:
            inside = key == "magic-items"
            found = found or inside
            item_indent = None
            continue
        if inside:
            if item_indent is None:
                item_indent = level
            if level == item_indent:
                names.add(key)
    if flat and not found:
        names = set(top_level_keys(text))
    return names


def magicspells_items(folder: Path) -> set[str]:
    """Magic item names that MagicSpells registers from a MagicSpells-Data folder, loaded with its rules
    (MagicConfig): general.yml, and spell*.yml at the top and in folders whose names start with "spells"."""
    files = [folder / "general.yml"] + sorted(folder.glob("spell*.yml"))
    for directory in sorted(folder.iterdir()):
        if directory.is_dir() and directory.name.startswith("spells"):
            files += sorted(directory.glob("spell*.yml"))
    names: set[str] = set()
    for file in files:
        if file.is_file():
            names |= magic_item_names(file.read_text(encoding="utf-8", errors="replace"))
    return names


# --- planning ----------------------------------------------------------------------------------------------------------

@dataclass
class Row:
    pb_id: str
    source_key: str | None
    recipient_uuid: str
    sender_name: str
    note: str
    rewards: list[dict]
    created_at: datetime
    expires_at: datetime | None
    claimed_at: datetime | None


@dataclass
class Plan:
    rows: list[Row] = field(default_factory=list)
    counts: Counter = field(default_factory=Counter)
    unknown_items: Counter = field(default_factory=Counter)  # converted names without a definition: rewards
    unknown_items_unclaimed: Counter = field(default_factory=Counter)
    kept_magicitem_commands: Counter = field(default_factory=Counter)  # `ms magicitem` commands that stay commands
    journal_mismatches: list[str] = field(default_factory=list)  # refuse
    journal_unmatched: list[str] = field(default_factory=list)  # journal keys no imported row carries: refuse
    journal_unconfirmed: list[str] = field(default_factory=list)  # refuse
    duplicate_keys: list[str] = field(default_factory=list)
    invalid: list[str] = field(default_factory=list)  # claimed mail, skipped
    invalid_unclaimed: list[str] = field(default_factory=list)  # refuse
    bad_times: list[str] = field(default_factory=list)  # lootbox numbers that are no time
    bad_times_unclaimed: list[str] = field(default_factory=list)  # refuse
    unresolved_names: Counter = field(default_factory=Counter)


@dataclass(frozen=True)
class JournalRow:
    source_key: str
    payload: dict
    confirmed: bool


def strip_extra_keys(reward):
    """A reward without the keys the old plugin ignored (it decoded with ignoreUnknownKeys), and whether any went."""
    if isinstance(reward, dict):
        keep = {"command": ("type", "command"), "item": ("type", "item", "amount")}.get(reward.get("type"))
        if keep and all(key in reward for key in keep) and set(reward) != set(keep):
            return {key: reward[key] for key in keep}, True
    return reward, False


def build_plan(records: list[dict], accounts: dict[str, list[str]], journal: dict[str, JournalRow] | None,
               known_items: set[str], now: datetime) -> Plan:
    """Turns PocketBase records into rows to insert. Pure: no database, no network.

    accounts maps lower(name) to the account UUIDs with that name; journal maps request_id to its row, or is None
    when sneakymail.command_requests doesn't exist. What would make the import wrong goes into the plan's refusal
    lists; refusals() turns them into reasons not to commit.
    """
    plan = Plan()
    counts = plan.counts
    used_keys: dict[str, str] = {}
    for record in sorted(records, key=lambda r: (r.get("created") or "", r["id"])):
        counts["records"] += 1
        available = bool(record.get("available"))
        state = "unclaimed" if available else "claimed"
        raw_rewards = record.get("rewards")
        if raw_rewards is None:
            raw_rewards = []
        if not isinstance(raw_rewards, list):
            (plan.invalid_unclaimed if available else plan.invalid).append(f"{record['id']} ({state}): rewards are not a list")
            counts[f"skipped: invalid rewards ({state})"] += 1
            continue

        stripped = []
        for reward in raw_rewards:
            reward, extra = strip_extra_keys(reward)
            counts["rewards with extra keys, stripped"] += extra
            stripped.append(reward)

        # A journal-keyed row keeps exactly what lom-phone-app was told was admitted: send() compares a retry with it.
        own_key = (record.get("source_key") or "").strip() or None
        entry = journal.get(record["id"]) if journal is not None and own_key is None else None

        # Lootbox expiry: the earliest time among the mail's lootbox commands.
        expiry = None
        for reward in stripped:
            if isinstance(reward, dict) and reward.get("type") == "command" and isinstance(reward.get("command"), str):
                try:
                    found = lootbox_expiry(reward["command"])
                except (OverflowError, ValueError, OSError):
                    line = f"{record['id']} ({state}): the lootbox number in {reward['command'][:80]} is not a time"
                    (plan.bad_times_unclaimed if available else plan.bad_times).append(line)
                    continue
                if found:
                    counts["lootbox commands in seconds"] += found[1]
                    expiry = found[0] if expiry is None else min(expiry, found[0])
        if available and expiry is not None and expiry < now:
            counts["skipped: unclaimed lootbox mail past its time"] += 1
            continue
        if entry is not None:
            expiry = None

        rewards = []
        for reward in stripped:
            if entry is None and isinstance(reward, dict) and set(reward) == {"type", "command"} \
                    and reward["type"] == "command" and isinstance(reward["command"], str):
                reward = convert_command(reward["command"])
                if reward["type"] == "command" and MAGICITEM_ANY.match(reward["command"].strip()):
                    plan.kept_magicitem_commands[reward["command"]] += 1
            rewards.append(reward)
        if not all(reward_valid(reward) for reward in rewards):
            line = f"{record['id']} ({state}): rewards {json.dumps(raw_rewards)[:200]}"
            (plan.invalid_unclaimed if available else plan.invalid).append(line)
            counts[f"skipped: invalid rewards ({state})"] += 1
            continue

        recipient = parse_uuid(record.get("recipient_uuid"))
        if recipient is None:
            if (record.get("recipient_uuid") or "").strip():
                counts["recipient_uuid that doesn't parse, looked up by name"] += 1
            name = (record.get("recipient_name") or "").strip().lower()
            candidates = sorted(set(accounts.get(name, []))) if name else []
            if len(candidates) != 1:
                reason = "several accounts" if candidates else "no account"
                counts[f"skipped: recipient not resolved ({reason}, {state})"] += 1
                plan.unresolved_names[record.get("recipient_name") or "(no name)"] += 1
                continue
            recipient = candidates[0]
            counts["recipients found by name"] += 1
        elif COMPACT_UUID.fullmatch((record.get("recipient_uuid") or "").strip()):
            counts["recipient_uuid without dashes"] += 1

        note = record.get("note") or ""
        sender = record.get("sender_name") or ""
        key = own_key
        if key is not None:
            counts["source keys of their own"] += 1
        elif entry is not None:
            key = entry.source_key
            counts["source keys from the journal"] += 1
            payload = entry.payload
            wanted = {"recipient": payload.get("recipient_uuid"), "sender": payload.get("sender_name"),
                      "note": payload.get("note"), "rewards": [{"type": "command", "command": payload.get("command")}]}
            have = {"recipient": recipient, "sender": sender, "note": note, "rewards": rewards}
            differ = [name for name in wanted if wanted[name] != have[name]]
            if differ:
                plan.journal_mismatches.append(f"{record['id']} (key {key}): {', '.join(differ)} differ from the journal")
        if key is not None and len(key) > 512:
            plan.invalid.append(f"{record['id']}: source key longer than 512 characters, imported without it")
            key = None
        if key is not None:
            if key in used_keys:
                plan.duplicate_keys.append(f"{record['id']}: key {key} already on {used_keys[key]}, imported without it")
                key = None
            else:
                used_keys[key] = record["id"]

        items = [reward for reward in rewards if reward["type"] == "item"]
        for item in items:
            counts["item rewards"] += 1
            if item["item"] not in known_items:
                plan.unknown_items[item["item"]] += 1
                if available:
                    plan.unknown_items_unclaimed[item["item"]] += 1
        counts["mails with item rewards"] += bool(items)
        counts[state] += 1
        counts["with expires_at"] += expiry is not None
        plan.rows.append(Row(
            pb_id=record["id"], source_key=key, recipient_uuid=recipient, sender_name=sender, note=note,
            rewards=rewards, created_at=parse_time(record["created"]), expires_at=expiry,
            claimed_at=None if available else parse_time(record["updated"]),
        ))

    if journal is not None:
        imported = {row.source_key for row in plan.rows}
        by_id = {record["id"]: record for record in records}
        for request_id, entry in sorted(journal.items()):
            if entry.source_key in imported:
                continue
            if request_id not in by_id:
                reason = "its PocketBase record is gone"
            elif (by_id[request_id].get("source_key") or "").strip():
                reason = "its record carries another source key"
            else:
                reason = "its record is not imported"
            plan.journal_unmatched.append(f"{request_id} (key {entry.source_key}): {reason}")
        plan.journal_unconfirmed = sorted(f"{request_id} (key {entry.source_key})"
                                          for request_id, entry in journal.items() if not entry.confirmed)
    return plan


ACCEPT_LOST = "--accept-lost-journal-keys"
ACCEPT_UNCONFIRMED = "--accept-unconfirmed-journal-rows"


def refusals(plan: Plan, accept_lost_keys: bool = False, accept_unconfirmed: bool = False) -> list[str]:
    """Why the import must not commit (empty: it may). Two of them can be accepted by naming the outcome."""
    reasons = []
    if plan.journal_mismatches:
        reasons.append(f"{len(plan.journal_mismatches)} journal-keyed rows differ from their journal payload")
    if plan.journal_unmatched and not accept_lost_keys:
        reasons.append(f"{len(plan.journal_unmatched)} journal keys would not be imported, so a lom-phone-app retry "
                       f"would send that mail again (accept that with {ACCEPT_LOST})")
    if plan.journal_unconfirmed and not accept_unconfirmed:
        reasons.append(f"{len(plan.journal_unconfirmed)} journal rows were never confirmed, so their mail may never have "
                       f"reached PocketBase and is not sent again (accept that with {ACCEPT_UNCONFIRMED})")
    if plan.invalid_unclaimed:
        reasons.append(f"{len(plan.invalid_unclaimed)} unclaimed mails have rewards that are still invalid")
    if plan.bad_times_unclaimed:
        reasons.append(f"{len(plan.bad_times_unclaimed)} unclaimed mails have a lootbox number that is no time")
    return reasons


def expected(plan: Plan) -> dict[str, int]:
    rows = plan.rows
    return {
        "mails": len(rows),
        "claimed": sum(1 for row in rows if row.claimed_at is not None),
        "unclaimed": sum(1 for row in rows if row.claimed_at is None),
        "with source_key": sum(1 for row in rows if row.source_key is not None),
        "with expires_at": sum(1 for row in rows if row.expires_at is not None),
        "item rewards": sum(1 for row in rows for reward in row.rewards if reward["type"] == "item"),
        "command rewards": sum(1 for row in rows for reward in row.rewards if reward["type"] == "command"),
    }


# --- PocketBase (read only) --------------------------------------------------------------------------------------------

def load_env_file(path: str | None, names: tuple[str, ...]) -> dict[str, str]:
    values = {name: os.environ[name] for name in names if os.environ.get(name)}
    if path:
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                if key.strip() in names:
                    values[key.strip()] = value.strip().strip('"').strip("'")
    return values


class PocketBase:
    def __init__(self, host: str, username: str, password: str):
        self.host = host.rstrip("/") if host.startswith("http") else "https://" + host.rstrip("/")
        self.token = None
        self.token = self._call("/api/collections/_superusers/auth-with-password",
                                {"identity": username, "password": password})["token"]

    def _call(self, path: str, body: dict | None = None) -> dict:
        request = urllib.request.Request(self.host + path, method="POST" if body is not None else "GET")
        request.add_header("User-Agent", "sneakymail-import/1")
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            request.add_header("Content-Type", "application/json")
        if self.token:
            request.add_header("Authorization", self.token)
        try:
            with urllib.request.urlopen(request, data, timeout=60) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            raise SystemExit(f"PocketBase answered {error.code} for {path.split('?')[0]}") from None

    def records(self, collection: str) -> list[dict]:
        items: list[dict] = []
        page = 1
        while True:
            query = urllib.parse.urlencode({"page": page, "perPage": 500, "skipTotal": 1, "sort": "id"})
            batch = self._call(f"/api/collections/{collection}/records?{query}")["items"]
            items += batch
            if len(batch) < 500:
                return items
            page += 1


# --- PostgreSQL --------------------------------------------------------------------------------------------------------

class Refused(Exception):
    """The database is not in the state this script writes to. Nothing is written."""


def prepare(cur) -> tuple[dict[str, list[str]], dict[str, JournalRow] | None]:
    """Checks and locks the target on an open transaction; returns the account names and the journal."""
    cur.execute("SELECT to_regclass('sneakydb.schema_migrations') IS NOT NULL, to_regclass('sneakymail.mail') IS NOT NULL")
    has_versions, has_mail = cur.fetchone()
    version = None
    if has_versions:
        cur.execute("SELECT max(version) FROM sneakydb.schema_migrations WHERE plugin = %s", (PLUGIN_ID,))
        version = cur.fetchone()[0]
    if version != SCHEMA_VERSION or not has_mail:
        raise Refused(f"{PLUGIN_ID} is at schema version {version}, this script writes version {SCHEMA_VERSION}")
    # Servers may still send mail; they wait for this transaction instead of slipping rows in before or between.
    cur.execute("LOCK TABLE sneakymail.mail IN EXCLUSIVE MODE")
    cur.execute("SELECT count(*) FROM sneakymail.mail")
    present = cur.fetchone()[0]
    if present:
        raise Refused(f"sneakymail.mail already holds {present} rows; the import runs only into an empty table")

    cur.execute("SELECT to_regclass('sneakyusermeta.accounts') IS NOT NULL")
    if not cur.fetchone()[0]:
        raise Refused("sneakyusermeta.accounts doesn't exist, so recipients known only by name can't be looked up")
    cur.execute("SELECT lower(name), uuid::text FROM sneakyusermeta.accounts")
    accounts: dict[str, list[str]] = {}
    for name, account in cur.fetchall():
        accounts.setdefault(name, []).append(account)

    cur.execute("SELECT to_regclass('sneakymail.command_requests') IS NOT NULL")
    if not cur.fetchone()[0]:
        return accounts, None
    cur.execute("SELECT request_id, source_key, payload, confirmed_at IS NOT NULL FROM sneakymail.command_requests")
    journal = {request_id: JournalRow(source_key, payload if isinstance(payload, dict) else json.loads(payload), confirmed)
               for request_id, source_key, payload, confirmed in cur.fetchall()}
    return accounts, journal


INSERT = """
    INSERT INTO sneakymail.mail (source_key, recipient_uuid, sender_name, note, rewards, created_at, expires_at, claimed_at)
    VALUES (%s, %s::uuid, %s, %s, %s::jsonb, %s, %s, %s)
"""


def write(cur, plan: Plan) -> dict[str, int]:
    """Inserts the plan's rows on the transaction prepare() opened and returns what the table holds afterwards."""
    cur.executemany(INSERT, [
        (row.source_key, row.recipient_uuid, row.sender_name, row.note, json.dumps(row.rewards), row.created_at,
         row.expires_at, row.claimed_at)
        for row in plan.rows
    ])
    return read_back(cur)


def read_back(cur) -> dict[str, int]:
    cur.execute("""
        SELECT count(*), count(claimed_at), count(*) - count(claimed_at), count(source_key), count(expires_at),
               coalesce(sum((SELECT count(*) FROM jsonb_array_elements(rewards) r WHERE r->>'type' = 'item')), 0),
               coalesce(sum((SELECT count(*) FROM jsonb_array_elements(rewards) r WHERE r->>'type' = 'command')), 0)
        FROM sneakymail.mail
    """)
    names = ("mails", "claimed", "unclaimed", "with source_key", "with expires_at", "item rewards", "command rewards")
    return dict(zip(names, (int(value) for value in cur.fetchone())))


def report(plan: Plan) -> None:
    for key, value in sorted(plan.counts.items()):
        print(f"  {key}: {value}")
    if plan.unknown_items:
        print("converted item names that no definition has (they still convert; SneakyMail refuses the mail until "
              "SneakyItems knows the item):")
        for name, total in sorted(plan.unknown_items.items()):
            print(f"  {name}: {total} rewards, {plan.unknown_items_unclaimed[name]} in unclaimed mail")
    else:
        print("every converted item name has a definition")
    if plan.kept_magicitem_commands:
        print("`ms magicitem` commands that stay commands (not `<name> [amount] {player}`):")
        for command, total in plan.kept_magicitem_commands.most_common():
            print(f"  {total} x {command}")
    if plan.unresolved_names:
        print(f"recipients skipped because their name didn't resolve: {len(plan.unresolved_names)} names, "
              f"{sum(plan.unresolved_names.values())} mails")
    for title, lines in (
        ("JOURNAL MISMATCH (lom-phone-app retrying these keys gets 'different payload')", plan.journal_mismatches),
        ("journal keys not imported (lom-phone-app retrying them would send the mail again)", plan.journal_unmatched),
        ("journal rows never confirmed (their mail may never have reached PocketBase)", plan.journal_unconfirmed),
        ("UNCLAIMED MAIL WITH INVALID REWARDS", plan.invalid_unclaimed),
        ("UNCLAIMED MAIL WITH A LOOTBOX NUMBER THAT IS NO TIME", plan.bad_times_unclaimed),
        ("claimed mail with a lootbox number that is no time (imported without expires_at)", plan.bad_times),
        ("duplicate source keys", plan.duplicate_keys),
        ("claimed mail skipped as invalid", plan.invalid),
    ):
        if lines:
            print(f"{title}: {len(lines)}")
            for line in lines:
                print("  " + line)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--pocketbase-env", help="file with POCKETBASE_HOST, POCKETBASE_USERNAME, POCKETBASE_PASSWORD")
    parser.add_argument("--pg-env", help="file with PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD")
    parser.add_argument("--magicspells-data", required=True, type=Path,
                        help="MagicSpells-Data checkout whose magic items the converted names are checked against")
    parser.add_argument("--items-file", action="append", default=[], type=Path,
                        help="another plugin's magic-items YAML that SneakyItems registers (repeatable)")
    parser.add_argument("--dry-run", action="store_true", help="do everything, then roll back")
    parser.add_argument(ACCEPT_LOST, action="store_true",
                        help="commit although some journal keys are not imported: a lom-phone-app retry of them sends the mail again")
    parser.add_argument(ACCEPT_UNCONFIRMED, action="store_true",
                        help="commit although some journal rows were never confirmed: their mail is not sent again")
    args = parser.parse_args(argv)

    import psycopg  # here, so the planning code and its tests need nothing installed

    known_items = magicspells_items(args.magicspells_data)
    for file in args.items_file:
        known_items |= magic_item_names(file.read_text(encoding="utf-8", errors="replace"), flat=True)
    print(f"magic item definitions: {len(known_items)}")

    pb_names = ("POCKETBASE_HOST", "POCKETBASE_USERNAME", "POCKETBASE_PASSWORD")
    pb_env = load_env_file(args.pocketbase_env, pb_names)
    if set(pb_names) - set(pb_env):
        raise SystemExit(f"missing {', '.join(sorted(set(pb_names) - set(pb_env)))}")
    pg_env = load_env_file(args.pg_env, ("PGHOST", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD"))

    records = PocketBase(*(pb_env[name] for name in pb_names)).records("lom2_mail")
    print(f"PocketBase: {len(records)} lom2_mail records")

    with psycopg.connect(host=pg_env.get("PGHOST"), port=pg_env.get("PGPORT"), dbname=pg_env.get("PGDATABASE"),
                         user=pg_env.get("PGUSER"), password=pg_env.get("PGPASSWORD"), connect_timeout=10) as connection:
        cur = connection.cursor()
        cur.execute("SELECT current_database(), current_user, now()")
        database, role, now = cur.fetchone()
        try:
            accounts, journal = prepare(cur)
        except Refused as refused:
            connection.rollback()
            print(f"REFUSED, nothing written ({database}): {refused}")
            return 1
        print(f"{database} as {role}: {sum(len(v) for v in accounts.values())} accounts, journal "
              + ("absent" if journal is None else f"{len(journal)} rows"))

        plan = build_plan(records, accounts, journal, known_items, now)
        report(plan)
        found = write(cur, plan)
        want = expected(plan)
        print("written:")
        for key in want:
            print(f"  {key}: {found[key]}" + ("" if found[key] == want[key] else f"  (expected {want[key]})"))
        wrong = [key for key in want if found[key] != want[key]]
        if wrong:
            connection.rollback()
            print("MISMATCH, rolled back:", ", ".join(wrong))
            return 1
        reasons = refusals(plan, args.accept_lost_journal_keys, args.accept_unconfirmed_journal_rows)
        if reasons:
            connection.rollback()
            print("REFUSED, rolled back" + (" (dry run)" if args.dry_run else "") + ":")
            for reason in reasons:
                print("  " + reason)
            return 1
        if args.dry_run:
            connection.rollback()
            print("dry run: rolled back")
        else:
            connection.commit()
            print("committed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
