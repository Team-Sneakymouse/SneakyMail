"""Tests for the writing half of import_pocketbase.py against a real PostgreSQL.

    SNEAKYMAIL_TEST_PG_DSN='host=127.0.0.1 port=55432 dbname=postgres user=postgres password=test' \\
        uv run --with "psycopg[binary]" python -m unittest discover -s tools

Needs a throwaway database: every test drops and recreates the sneakymail, sneakydb and sneakyusermeta schemas.
Without the variable these tests are skipped, which proves nothing about them.
"""

import os
import unittest
from datetime import datetime, timezone
from pathlib import Path

from import_pocketbase import Refused, build_plan, expected, prepare, write

DSN = os.environ.get("SNEAKYMAIL_TEST_PG_DSN")
MIGRATIONS = Path(__file__).resolve().parent.parent / "src/main/resources/db/migrations"
NOW = datetime(2026, 11, 18, 12, 0, tzinfo=timezone.utc)
ALICE = "11111111-1111-4111-8111-111111111111"
BOB = "22222222-2222-4222-8222-222222222222"


def cmd(command):
    return {"type": "command", "command": command}


def record(pb_id, recipient=ALICE, name="", available=True, rewards=None, source_key=""):
    return {"id": pb_id, "sender_name": "Jimmy The Rat", "recipient_uuid": recipient, "recipient_name": name,
            "note": "line one\nline two", "available": available,
            "rewards": [cmd("say {player}")] if rewards is None else rewards, "source_key": source_key,
            "created": "2026-01-02 03:04:05.678Z", "updated": "2026-01-03 00:00:00.000Z"}


@unittest.skipUnless(DSN, "SNEAKYMAIL_TEST_PG_DSN not set: the import's writes are NOT tested")
class ImportAgainstPostgres(unittest.TestCase):
    def setUp(self):
        import psycopg

        self.db = psycopg.connect(DSN, autocommit=True)
        for statement in (
            "DROP SCHEMA IF EXISTS sneakymail CASCADE", "DROP SCHEMA IF EXISTS sneakydb CASCADE",
            "DROP SCHEMA IF EXISTS sneakyusermeta CASCADE",
            "CREATE SCHEMA sneakymail", "CREATE SCHEMA sneakydb", "CREATE SCHEMA sneakyusermeta",
            "CREATE TABLE sneakydb.schema_migrations (plugin text NOT NULL, version integer NOT NULL, name text NOT NULL,"
            " checksum text, applied_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (plugin, version))",
            "CREATE TABLE sneakyusermeta.accounts (uuid uuid PRIMARY KEY, name text NOT NULL)",
        ):
            self.db.execute(statement)
        for script in sorted(MIGRATIONS.glob("V*.sql")):
            self.db.execute(script.read_text(encoding="utf-8"))
            self.db.execute("INSERT INTO sneakydb.schema_migrations (plugin, version, name) VALUES ('sneakymail', %s, %s)",
                            (int(script.name[1:].split("__")[0]), script.name))
        self.db.execute("INSERT INTO sneakyusermeta.accounts VALUES (%s, 'Bob')", (BOB,))
        self.conn = psycopg.connect(DSN)
        self.cur = self.conn.cursor()

    def tearDown(self):
        self.conn.close()
        self.db.close()

    def journal_table(self):
        self.db.execute("CREATE TABLE sneakymail.command_requests (source_key text PRIMARY KEY, request_id text NOT NULL UNIQUE,"
                        " payload jsonb NOT NULL, confirmed_at timestamptz)")

    def test_imports_into_the_v001_table_and_reads_back_what_it_planned(self):
        self.journal_table()
        payload = '{"recipient_uuid": "%s", "sender_name": "Jimmy The Rat", "note": "line one\\nline two", "command": "say {player}"}' % ALICE
        self.db.execute("INSERT INTO sneakymail.command_requests VALUES ('lomphone:1', 'phone', %s::jsonb, now())", (payload,))
        accounts, journal = prepare(self.cur)
        self.assertEqual({"bob": [BOB]}, accounts)
        self.assertEqual({"phone"}, set(journal))
        plan = build_plan([
            record("claimed", available=False, rewards=[cmd("ms magicitem item-EXP 3 {player}"), cmd(f"lootbox {{player}} 1893456000000 4")]),
            record("by-name", recipient="", name="bob", rewards=[]),
            record("shop", source_key="shop_1"),
            record("phone"),
            record("nobody", recipient="", name="nobody"),
        ], accounts, journal, {"item-EXP"}, NOW)
        self.assertEqual([], plan.journal_mismatches)
        found = write(self.cur, plan)
        self.assertEqual(expected(plan), found)
        self.assertEqual({"mails": 4, "claimed": 1, "unclaimed": 3, "with source_key": 2, "with expires_at": 1,
                          "item rewards": 1, "command rewards": 3}, found)
        self.conn.commit()

        # Same creation time, so PocketBase id order: by-name, claimed, phone, shop.
        rows = self.db.execute("SELECT source_key, recipient_uuid::text, sender_name, note, rewards, created_at, expires_at,"
                               " claimed_at FROM sneakymail.mail ORDER BY id").fetchall()
        self.assertEqual((None, BOB, []), (rows[0][0], rows[0][1], rows[0][4]))
        self.assertEqual((None, ALICE, "Jimmy The Rat", "line one\nline two",
                          [{"type": "item", "item": "item-EXP", "amount": 3}, cmd("lootbox {player} 1893456000000 4")],
                          datetime(2026, 1, 2, 3, 4, 5, 678000, tzinfo=timezone.utc),
                          datetime(2030, 1, 1, tzinfo=timezone.utc), datetime(2026, 1, 3, tzinfo=timezone.utc)), rows[1])
        self.assertEqual(["lomphone:1", "shop_1"], [rows[2][0], rows[3][0]])
        # The journal's payload, sent again by lom-phone-app, finds the imported mail.
        mail_id = self.db.execute("SELECT id FROM sneakymail.mail WHERE source_key = 'lomphone:1'").fetchone()[0]
        again = self.db.execute("SELECT sneakymail.send('lomphone:1', %s, 'Jimmy The Rat', %s, %s::jsonb)",
                                (ALICE, "line one\nline two", '[{"type":"command","command":"say {player}"}]')).fetchone()[0]
        self.assertEqual(mail_id, again)

    def test_without_a_journal_table(self):
        accounts, journal = prepare(self.cur)
        self.assertIsNone(journal)

    def test_refuses_a_table_that_holds_mail(self):
        self.db.execute("INSERT INTO sneakymail.mail (recipient_uuid, sender_name) VALUES (%s, 'Staff')", (ALICE,))
        with self.assertRaisesRegex(Refused, "already holds 1 rows"):
            prepare(self.cur)

    def test_refuses_another_schema_version(self):
        self.db.execute("INSERT INTO sneakydb.schema_migrations (plugin, version, name) VALUES ('sneakymail', 2, 'V002__x.sql')")
        with self.assertRaisesRegex(Refused, "schema version 2"):
            prepare(self.cur)
        self.conn.rollback()
        self.db.execute("DELETE FROM sneakydb.schema_migrations")
        with self.assertRaisesRegex(Refused, "schema version None"):
            prepare(self.cur)
        self.conn.rollback()
        self.db.execute("DROP SCHEMA sneakydb CASCADE")
        with self.assertRaisesRegex(Refused, "schema version None"):
            prepare(self.cur)

    def test_holds_writers_off_until_it_ends(self):
        import psycopg

        prepare(self.cur)
        with psycopg.connect(DSN, autocommit=True) as other:
            other.execute("SET lock_timeout = '200ms'")
            with self.assertRaises(psycopg.errors.LockNotAvailable):
                other.execute("SELECT sneakymail.send(null, %s, 'Staff', '', '[]')", (ALICE,))


if __name__ == "__main__":
    unittest.main()
