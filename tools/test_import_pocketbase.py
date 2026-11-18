"""Tests for the planning half of import_pocketbase.py (no database, no network).

    python3 -m unittest discover -s tools
"""

import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from import_pocketbase import (JournalRow, build_plan, convert_command, expected, lootbox_expiry, magic_item_names,
                               magicspells_items, parse_uuid, refusals, reward_valid, strip_extra_keys)

NOW = datetime(2026, 11, 18, 12, 0, tzinfo=timezone.utc)
CREATED = "2026-01-02 03:04:05.678Z"
UPDATED = "2026-01-03 00:00:00.000Z"
ALICE = "11111111-1111-4111-8111-111111111111"
BOB = "22222222-2222-4222-8222-222222222222"
PAST_MS = 1756166400000  # 2025-08-26
FUTURE_MS = 1893456000000  # 2030-01-01
PAST_S = 1771721959  # 2026-02-22, written in seconds


def cmd(command):
    return {"type": "command", "command": command}


def record(pb_id="rec000000000001", recipient=ALICE, name="", available=True, rewards=None, note="Hello",
           sender="<gold>Mother Mouse</gold>", source_key="", created=CREATED, updated=UPDATED):
    return {"id": pb_id, "sender_uuid": "", "sender_name": sender, "recipient_uuid": recipient, "recipient_name": name,
            "note": note, "available": available, "rewards": [cmd("say {player}")] if rewards is None else rewards,
            "source_key": source_key, "created": created, "updated": updated}


def plan_of(*records, accounts=None, journal=None, known=frozenset({"item-EXP", "item-glue"})):
    return build_plan(list(records), accounts or {}, journal, set(known), NOW)


class Conversion(unittest.TestCase):
    def test_uuid_forms(self):
        self.assertEqual(ALICE, parse_uuid(ALICE))
        self.assertEqual(ALICE, parse_uuid(ALICE.replace("-", "")))
        self.assertEqual(ALICE, parse_uuid(" " + ALICE.upper() + " "))
        for bad in ("", None, "null", "1111", ALICE[:-1], ALICE.replace("-", "") + "0", "g" * 32):
            self.assertIsNone(parse_uuid(bad), bad)

    def test_lootbox_time_in_milliseconds_or_seconds(self):
        self.assertEqual((datetime(2025, 8, 26, tzinfo=timezone.utc), False), lootbox_expiry(f"lootbox {{player}} {PAST_MS}"))
        self.assertEqual((datetime(2025, 8, 26, tzinfo=timezone.utc), False), lootbox_expiry(f"lootbox {{player}} {PAST_MS} 46"))
        self.assertEqual((datetime.fromtimestamp(PAST_S, tz=timezone.utc), True), lootbox_expiry(f"lootbox {{player}} {PAST_S} 15"))
        for other in ("lootbox open box {player}", "lootbox {player}", "say lootbox {player} 1", "lootbox Bob 1756166400000"):
            self.assertIsNone(lootbox_expiry(other), other)

    def test_magicitem_commands_become_item_rewards(self):
        self.assertEqual({"type": "item", "item": "item-EXP", "amount": 1}, convert_command("ms magicitem item-EXP {player}"))
        self.assertEqual({"type": "item", "item": "item-knifecase-golden", "amount": 3},
                         convert_command("ms magicitem item-knifecase-golden 3 {player}"))
        self.assertEqual({"type": "item", "item": "item-EXP", "amount": 2304}, convert_command("ms magicitem item-EXP 2304 {player}"))

    def test_other_commands_stay_commands(self):
        for command in (
            "ms magicitem item-EXP {player} 3",  # MagicSpells reads "3" as a player name: it never worked
            "ms magicitem item-EXP 0 {player}",
            "ms magicitem item-EXP 2305 {player}",
            "ms magicitem item-EXP Bob",
            "ms magicitem item-EXP",
            "ms cast as {player} purchase-dragoncoins",
            "lootbox {player} 1756166400000",
            "prototypekit {player}",
        ):
            self.assertEqual(cmd(command), convert_command(command), command)

    def test_reward_valid_matches_the_database_rule(self):
        self.assertTrue(reward_valid(cmd("x")))
        self.assertTrue(reward_valid({"type": "item", "item": "x", "amount": 2304}))
        for bad in (cmd(""), {"type": "command", "command": 5}, {"type": "command", "command": "x", "as": 1},
                    {"type": "item", "item": "x", "amount": 0}, {"type": "item", "item": "x", "amount": True},
                    {"type": "item", "item": "x", "amount": 1.0}, {"type": "item", "item": "", "amount": 1},
                    {"type": "money"}, "x", None):
            self.assertFalse(reward_valid(bad), bad)


class Plan(unittest.TestCase):
    def test_claimed_and_unclaimed_times(self):
        plan = plan_of(record("a", available=False), record("b"))
        claimed, unclaimed = plan.rows
        self.assertEqual(datetime(2026, 1, 2, 3, 4, 5, 678000, tzinfo=timezone.utc), claimed.created_at)
        self.assertEqual(datetime(2026, 1, 3, tzinfo=timezone.utc), claimed.claimed_at)
        self.assertIsNone(unclaimed.claimed_at)
        self.assertIsNone(unclaimed.expires_at)
        self.assertEqual(("<gold>Mother Mouse</gold>", "Hello", [cmd("say {player}")]),
                         (claimed.sender_name, claimed.note, claimed.rewards))

    def test_unclaimed_lootbox_mail_past_its_time_is_skipped(self):
        plan = plan_of(
            record("old", rewards=[cmd(f"lootbox {{player}} {PAST_MS}")] * 3),
            record("old-seconds", rewards=[cmd(f"lootbox {{player}} {PAST_S} 15")]),
            record("future", rewards=[cmd(f"lootbox {{player}} {FUTURE_MS} 2")]),
            record("claimed-old", available=False, rewards=[cmd(f"lootbox {{player}} {PAST_MS}")]),
            record("mixed", rewards=[cmd(f"lootbox {{player}} {FUTURE_MS}"), cmd(f"lootbox {{player}} {PAST_MS}")]),
        )
        rows = {row.pb_id: row for row in plan.rows}
        self.assertEqual({"future", "claimed-old"}, set(rows))
        self.assertEqual(3, plan.counts["skipped: unclaimed lootbox mail past its time"])
        self.assertEqual(datetime(2030, 1, 1, tzinfo=timezone.utc), rows["future"].expires_at)
        self.assertEqual(datetime(2025, 8, 26, tzinfo=timezone.utc), rows["claimed-old"].expires_at, "claimed lootbox mail keeps its time")
        self.assertEqual(1, plan.counts["lootbox commands in seconds"])

    def test_recipients_by_uuid_or_name(self):
        accounts = {"bob": [BOB], "twins": [ALICE, BOB], "alice": [ALICE]}
        plan = plan_of(
            record("dashed", recipient=ALICE, name="bob"),
            record("compact", recipient=ALICE.replace("-", "")),
            record("by-name", recipient="", name="Bob"),
            record("broken-uuid", recipient="not-a-uuid", name="ALICE"),
            record("unknown", recipient="", name="Nobody", available=False),
            record("ambiguous", recipient="", name="twins"),
            record("nameless", recipient=""),
            accounts=accounts,
        )
        self.assertEqual({"dashed": ALICE, "compact": ALICE, "by-name": BOB, "broken-uuid": ALICE},
                         {row.pb_id: row.recipient_uuid for row in plan.rows})
        self.assertEqual(1, plan.counts["skipped: recipient not resolved (no account, claimed)"])
        self.assertEqual(1, plan.counts["skipped: recipient not resolved (no account, unclaimed)"])
        self.assertEqual(1, plan.counts["skipped: recipient not resolved (several accounts, unclaimed)"])
        self.assertEqual(2, plan.counts["recipients found by name"])
        self.assertEqual(1, plan.counts["recipient_uuid without dashes"])

    def test_magicitem_rewards_convert_and_unknown_names_are_listed(self):
        plan = plan_of(
            record("a", rewards=[cmd("ms magicitem item-EXP {player}"), cmd("ms magicitem item-gone 2 {player}"),
                                 cmd("ms cast as {player} x")]),
            record("b", available=False, rewards=[cmd("ms magicitem item-gone {player}"), cmd("ms magicitem item-EXP {player} 3")]),
        )
        self.assertEqual([{"type": "item", "item": "item-EXP", "amount": 1}, {"type": "item", "item": "item-gone", "amount": 2},
                          cmd("ms cast as {player} x")], plan.rows[0].rewards)
        self.assertEqual({"item-gone": 2}, dict(plan.unknown_items))
        self.assertEqual({"item-gone": 1}, dict(plan.unknown_items_unclaimed))
        self.assertEqual({"ms magicitem item-EXP {player} 3": 1}, dict(plan.kept_magicitem_commands))
        self.assertEqual(3, plan.counts["item rewards"])
        self.assertEqual(2, plan.counts["mails with item rewards"])

    def test_empty_or_null_rewards_and_notes(self):
        plan = plan_of(record("a", rewards=[], note=None), {**record("b"), "rewards": None})
        self.assertEqual([[], []], [row.rewards for row in plan.rows])
        self.assertEqual("", plan.rows[0].note)

    def test_invalid_rewards_skip_claimed_mail_and_refuse_unclaimed_mail(self):
        plan = plan_of(record("a", available=False, rewards=[cmd("")]),
                       record("b", available=False, rewards=[{"type": "item", "item": "x"}]),
                       {**record("c", available=False), "rewards": {"type": "command"}})
        self.assertEqual([], plan.rows)
        self.assertEqual(3, plan.counts["skipped: invalid rewards (claimed)"])
        self.assertEqual(3, len(plan.invalid))
        self.assertEqual([], refusals(plan))

        plan = plan_of(record("d", rewards=[{"type": "money", "amount": 5}]), {**record("e"), "rewards": "x"})
        self.assertEqual(2, len(plan.invalid_unclaimed))
        self.assertEqual(["2 unclaimed mails have rewards that are still invalid"], refusals(plan))

    def test_keys_the_old_plugin_ignored_are_stripped(self):
        self.assertEqual((cmd("x"), True), strip_extra_keys({"type": "command", "command": "x", "asConsole": True}))
        self.assertEqual(({"type": "item", "item": "i", "amount": 2}, True),
                         strip_extra_keys({"type": "item", "item": "i", "amount": 2, "note": ""}))
        self.assertEqual((cmd("x"), False), strip_extra_keys(cmd("x")))
        self.assertEqual(({"type": "command", "x": 1}, False), strip_extra_keys({"type": "command", "x": 1}))
        plan = plan_of(record("a", rewards=[{"type": "command", "command": "ms magicitem item-EXP {player}", "extra": 1},
                                             {"type": "command", "command": "say {player}", "asConsole": True}]))
        self.assertEqual([{"type": "item", "item": "item-EXP", "amount": 1}, cmd("say {player}")], plan.rows[0].rewards)
        self.assertEqual(2, plan.counts["rewards with extra keys, stripped"])
        self.assertEqual([], refusals(plan))

    def test_a_lootbox_number_that_is_no_time_is_reported(self):
        huge = "9" * 30
        with self.assertRaises((OverflowError, ValueError, OSError)):
            lootbox_expiry(f"lootbox {{player}} {huge}")
        plan = plan_of(record("claimed", available=False, rewards=[cmd(f"lootbox {{player}} {huge}")]),
                       record("unclaimed", rewards=[cmd(f"lootbox {{player}} {huge} 3")]))
        self.assertEqual({"claimed": None, "unclaimed": None}, {row.pb_id: row.expires_at for row in plan.rows})
        self.assertEqual(1, len(plan.bad_times))
        self.assertEqual(1, len(plan.bad_times_unclaimed))
        self.assertIn("unclaimed (unclaimed)", plan.bad_times_unclaimed[0])
        self.assertEqual(["1 unclaimed mails have a lootbox number that is no time"], refusals(plan))

    def test_source_keys_of_their_own_are_kept_exactly(self):
        plan = plan_of(record("a", source_key="shop_1"), record("b", source_key="  "), record("c", source_key=" shop_2 "),
                       record("d", source_key=""), {**record("e"), "source_key": None})
        self.assertEqual({"a": "shop_1", "b": "  ", "c": " shop_2 ", "d": None, "e": None},
                         {row.pb_id: row.source_key for row in plan.rows})
        self.assertEqual([], refusals(plan))

    def test_two_records_with_one_key_refuse_the_import(self):
        payload = {"recipient_uuid": ALICE, "sender_name": "<gold>Mother Mouse</gold>", "note": "Hello", "command": "say {player}"}
        # PocketBase's unique index stops two records with one key of their own; a journal key can still collide.
        plan = plan_of(record("a", source_key="lomphone:1"), record("b"), journal={"b": JournalRow("lomphone:1", payload, True)})
        self.assertEqual({"a": "lomphone:1", "b": "lomphone:1"}, {row.pb_id: row.source_key for row in plan.rows},
                         "no key is dropped to get past uniqueness")
        self.assertEqual(["b: key 'lomphone:1' is also on a"], plan.duplicate_keys)
        self.assertIn("1 records would carry a source key another record already carries",
                      refusals(plan, accept_lost_keys=True, accept_unconfirmed=True))

    def test_a_key_the_table_cannot_hold_refuses_the_import(self):
        plan = plan_of(record("a", source_key="k" * 513), record("b", source_key="k" * 512))
        self.assertEqual(["a: source key of 513 characters (the table takes 1 to 512)"], plan.invalid_keys)
        self.assertEqual(["1 records carry a source key the table can't hold"], refusals(plan))

    def test_source_keys_from_the_journal(self):
        payload = {"recipient_uuid": ALICE, "sender_name": "<gold>Mother Mouse</gold>", "note": "Hello", "command": "say {player}"}
        journal = {
            "matching": JournalRow("lomphone:1", payload, True),
            "changed": JournalRow("lomphone:2", {**payload, "note": "Other", "command": "say other"}, True),
            "own-key": JournalRow("lomphone:3", payload, True),
            "skipped": JournalRow("lomphone:4", payload, False),
            "gone": JournalRow("lomphone:5", payload, True),
            "converted": JournalRow("lomphone:6", {**payload, "command": "ms magicitem item-EXP {player}"}, True),
            "lootbox": JournalRow("lomphone:7", {**payload, "command": f"lootbox {{player}} {FUTURE_MS}"}, True),
        }
        plan = plan_of(
            record("matching"), record("changed"), record("own-key", source_key="lomphone:other"),
            record("skipped", recipient="", name="nobody"),
            record("converted", rewards=[cmd("ms magicitem item-EXP {player}")]),
            record("lootbox", rewards=[cmd(f"lootbox {{player}} {FUTURE_MS}")]),
            record("no-entry", rewards=[cmd("ms magicitem item-EXP {player}")]),
            journal=journal,
        )
        self.assertEqual({"matching": "lomphone:1", "changed": "lomphone:2", "own-key": "lomphone:other",
                          "converted": "lomphone:6", "lootbox": "lomphone:7", "no-entry": None},
                         {row.pb_id: row.source_key for row in plan.rows})
        rows = {row.pb_id: row for row in plan.rows}
        # Journal-keyed rows keep the admitted command and no expiry, so a retry with the same arguments matches send().
        self.assertEqual([cmd("ms magicitem item-EXP {player}")], rows["converted"].rewards)
        self.assertEqual(([cmd(f"lootbox {{player}} {FUTURE_MS}")], None), (rows["lootbox"].rewards, rows["lootbox"].expires_at))
        self.assertEqual([{"type": "item", "item": "item-EXP", "amount": 1}], rows["no-entry"].rewards)
        self.assertEqual(["changed (key lomphone:2): note, rewards differ from the journal"], plan.journal_mismatches)
        self.assertEqual(["gone (key lomphone:5): its PocketBase record is gone",
                          "own-key (key lomphone:3): its record carries another source key",
                          "skipped (key lomphone:4): its record is not imported"], plan.journal_unmatched)
        self.assertEqual(["skipped (key lomphone:4)"], plan.journal_unconfirmed)
        self.assertEqual(4, plan.counts["source keys from the journal"])
        self.assertEqual(["own-key: carries key lomphone:other, but the journal gives it key lomphone:3"], plan.journal_conflicts)
        self.assertEqual(4, len(refusals(plan)))
        self.assertEqual(["1 journal-keyed rows differ from their journal payload",
                          "1 records carry a source key the journal pairs differently"],
                         refusals(plan, accept_lost_keys=True, accept_unconfirmed=True), "mismatches and conflicts are never accepted")

    def test_a_record_carrying_its_journal_key_itself_is_checked_and_kept_exact(self):
        payload = {"recipient_uuid": ALICE, "sender_name": "<gold>Mother Mouse</gold>", "note": "Hello",
                   "command": "ms magicitem item-EXP {player}"}
        journal = {"self-keyed": JournalRow("lomphone:1", payload, True),
                   "changed": JournalRow("lomphone:2", payload, True),
                   "elsewhere": JournalRow("lomphone:3", payload, True)}
        plan = plan_of(
            record("self-keyed", source_key="lomphone:1", rewards=[cmd("ms magicitem item-EXP {player}")]),
            record("changed", source_key="lomphone:2", recipient=BOB, rewards=[cmd(f"lootbox {{player}} {FUTURE_MS}")]),
            record("thief", source_key="lomphone:3", rewards=[cmd("ms magicitem item-EXP {player}")]),
            journal=journal,
        )
        rows = {row.pb_id: row for row in plan.rows}
        self.assertEqual(([cmd("ms magicitem item-EXP {player}")], None, "lomphone:1"),
                         (rows["self-keyed"].rewards, rows["self-keyed"].expires_at, rows["self-keyed"].source_key))
        self.assertIsNone(rows["changed"].expires_at, "a journal key never gets an expiry")
        self.assertEqual(["changed (key lomphone:2): recipient, rewards differ from the journal"], plan.journal_mismatches)
        self.assertEqual(["thief: carries key lomphone:3, which the journal gives to request elsewhere"], plan.journal_conflicts)
        self.assertEqual([{"type": "item", "item": "item-EXP", "amount": 1}], rows["thief"].rewards,
                         "a conflicting record is not treated as the journal's")
        self.assertEqual(2, plan.counts["source keys of their own that are in the journal"])
        self.assertEqual(2, len(refusals(plan, accept_lost_keys=True, accept_unconfirmed=True)))

    def test_refusals_for_journal_keys_can_be_accepted_by_naming_the_outcome(self):
        payload = {"recipient_uuid": ALICE, "sender_name": "<gold>Mother Mouse</gold>", "note": "Hello", "command": "say {player}"}
        plan = plan_of(record("kept"), journal={"kept": JournalRow("k1", payload, False), "gone": JournalRow("k2", payload, True)})
        self.assertEqual([], plan.journal_mismatches)
        reasons = refusals(plan)
        self.assertEqual(2, len(reasons))
        self.assertIn("--accept-lost-journal-keys", reasons[0])
        self.assertIn("--accept-unconfirmed-journal-rows", reasons[1])
        self.assertEqual(1, len(refusals(plan, accept_lost_keys=True)))
        self.assertEqual(1, len(refusals(plan, accept_unconfirmed=True)))
        self.assertEqual([], refusals(plan, accept_lost_keys=True, accept_unconfirmed=True))

    def test_without_a_journal_records_keep_only_their_own_keys(self):
        plan = plan_of(record("a"), journal=None)
        self.assertIsNone(plan.rows[0].source_key)
        self.assertEqual([], plan.journal_unmatched)

    def test_rows_follow_creation_order_and_expected_counts(self):
        plan = plan_of(
            record("late", created="2026-05-01 00:00:00.000Z", rewards=[cmd("ms magicitem item-glue 3 {player}")]),
            record("early", available=False, source_key="k", created="2025-01-01 00:00:00.000Z",
                   rewards=[cmd(f"lootbox {{player}} {FUTURE_MS}")]),
        )
        self.assertEqual(["early", "late"], [row.pb_id for row in plan.rows])
        self.assertEqual({"mails": 2, "claimed": 1, "unclaimed": 1, "with source_key": 1, "with expires_at": 1,
                          "item rewards": 1, "command rewards": 1}, expected(plan))


class Definitions(unittest.TestCase):
    def test_magic_item_names_reads_the_first_level_under_magic_items(self):
        text = """# comment
spell-one:
    spell-class: ".BuffSpell"
magic-items:
    # comment
    item-a:
        type: "paper"
        name: "&cA: with colon"
        lore:
            - "item-fake: no"
        description: |
          not-a-key: here
    "item-b": "stone"
    'item-c':
        type: paper

    item-d: {type: paper}
other:
    item-e:
        type: paper
"""
        self.assertEqual({"item-a", "item-b", "item-c", "item-d"}, magic_item_names(text))
        self.assertEqual(set(), magic_item_names("item-x:\n  type: paper\n"))
        self.assertEqual({"item-x"}, magic_item_names("item-x:\n  type: paper\n", flat=True))
        self.assertEqual({"item-y"}, magic_item_names("item-x: 1\nmagic-items:\n  item-y:\n    type: paper\n", flat=True))

    def test_magicspells_items_loads_files_the_way_magicspells_does(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "spells-items" / "disabled").mkdir(parents=True)
            (root / "other").mkdir()
            items = "magic-items:\n  {}:\n    type: paper\n"
            (root / "general.yml").write_text(items.format("from-general"), encoding="utf-8")
            (root / "spells-top.yml").write_text(items.format("from-top"), encoding="utf-8")
            (root / "spells-items" / "spells-item-a.yml").write_text(items.format("from-folder"), encoding="utf-8")
            (root / "spells-items" / "item-b.yml").write_text(items.format("not-spell-prefixed"), encoding="utf-8")
            (root / "spells-items" / "disabled" / "spells-c.yml").write_text(items.format("too-deep"), encoding="utf-8")
            (root / "other" / "spells-d.yml").write_text(items.format("not-a-spells-folder"), encoding="utf-8")
            (root / "mana.yml").write_text(items.format("from-mana"), encoding="utf-8")
            self.assertEqual({"from-general", "from-top", "from-folder"}, magicspells_items(root))


if __name__ == "__main__":
    unittest.main()
