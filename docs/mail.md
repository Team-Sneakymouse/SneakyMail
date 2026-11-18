# Mail in PostgreSQL

SneakyMail keeps mail in the table `sneakymail.mail` and nothing else: no PocketBase, no delivery worker, no journal.
Whoever sends mail inserts a row (best through `sneakymail.send`); the plugin shows unclaimed mail to its recipient
in `/mail` and hands out the rewards once, when the recipient claims it.

## The table and its functions

`src/main/resources/db/migrations/V001__mail.sql` creates them through SneakyDB's `migrate("sneakymail", ...)`. Its
names and signatures are a contract with Mother-Mouse-Discord, n8n workflows and hand-written SQL: change them only
with a new script and with those callers.

| Object | What it does |
|---|---|
| `sneakymail.mail` | `id`, `source_key` (unique, optional), `recipient_uuid`, `sender_name`, `note`, `rewards` (jsonb), `created_at`, `expires_at`, `claimed_at` (null until claimed) |
| `sneakymail.rewards_valid(jsonb)` | The table's check: a JSON array of `{"type":"command","command":"..."}` and `{"type":"item","item":"<SneakyItems internal name>","amount":1..2304}`, no other keys. `[]` is valid |
| `sneakymail.send(source_key, recipient, sender_name, note, rewards, expires_at default null)` | Inserts and returns the id. With a source key it is idempotent: the same key with the same recipient, sender, note, rewards and expiry returns the first mail's id, also for concurrent calls; different content raises SQLSTATE `23505`. A null note becomes `''`. `security definer`; EXECUTE is revoked from PUBLIC, an operator grants it. A source key is remembered only while its row exists, so never delete claimed mail rows |
| `sneakymail.delete_expired()` | Deletes unclaimed mail whose `expires_at` has passed and returns the count. pg_cron runs it every 10 minutes as the plugin's role (scheduled by an operator) |
| trigger `mail_notify` | `pg_notify('sneakymail', ...)` with `{"event":"new","id":<id>,"recipient":"<uuid>"}` after an insert, `"claimed"` when `claimed_at` goes from null to a value, `"unclaimed"` when it goes from a value back to null, `"deleted"` after a delete (same payload shape for all four) |

Rewards:

- `command` runs as the console with `{player}` replaced by the recipient's name. Only trusted senders write commands;
  the note is display text (MiniMessage) and never becomes command input.
- `item` gives `amount` copies of a SneakyItems magic item by internal name. MagicSpells registers its magic items in
  SneakyItems under their `magic-items:` keys, so `ms magicitem item-EXP {player}` and
  `{"type":"item","item":"item-EXP","amount":1}` give the same item.

Sending by hand (DBeaver): `select sneakymail.send(null, '<uuid>', '<gold>Staff</gold>', 'Thanks!', '[{"type":"item","item":"item-EXP","amount":5}]');`
or a plain insert. Either shows up in game at once for an online recipient.

## The plugin

- **Cache:** only online players' unclaimed mail, loaded when they join and dropped when they quit (`MailCache`). Every
  notification on channel `sneakymail` reloads the recipient from the database (`claimed` and `deleted` also drop the
  mail at once); when the subscription (re)connects, every online player is reloaded, because notifications sent while
  it was down are lost. A load that was running while a mail was claimed can't bring that mail back; the load after
  it decides, so a mail given back (`unclaimed`) shows up again.
  Notification and database callbacks run off the server thread and are rescheduled onto it.
- **Claiming** (`MailClaims`): on the server thread SneakyMail checks that SneakyItems knows every item reward and
  that the player's inventory has room for all of them; otherwise the mail stays and the player is told to tell staff
  or to make room. Then, off the server thread, the claim statement, in its own transaction with
  `set local lock_timeout = '5s'` and `set local statement_timeout = '15s'`:

  ```sql
  update sneakymail.mail set claimed_at = now() where id = ? and recipient_uuid = ? and claimed_at is null returning rewards
  ```

  Rewards are handed out only when a row comes back ("already collected" otherwise), in their order, on the server
  thread. Items that no longer fit drop at the player's feet. A reward that throws, a command that `dispatchCommand`
  refuses, or an item SneakyItems no longer knows is logged SEVERE with the mail id, the player and that reward's
  JSON, and the remaining rewards still run; a claim is never undone once any reward was given.
- **A row locked elsewhere** (an idle DBeaver transaction, say) makes the claim give up after the lock timeout: the
  transaction rolls back, so the mail is definitely not claimed. The player is told to try again, a warning is
  logged, and the mail can be clicked again. Any other failure of the claim statement is uncertain (see below).
- **Log lines per claim:** `Claiming mail <id> for <player> (<uuid>)` before the claim statement; for every reward,
  before the next one starts, either `Gave reward <i>/<n> of mail <id> to <player> (<uuid>): <reward JSON>` or a
  SEVERE `Reward <i>/<n> of mail <id> for ... failed (...)`; then `Redeemed mail <id> for <player> (<uuid>): <k> of
  <n> rewards given`. No crash bookkeeping is kept in the database.
- **An interrupted or uncertain redemption** (a `Claiming` line without its `Redeemed` line, a SEVERE line about mail
  `<id>`) is uncertain, not "nothing was given": a crash can come after some or all rewards ran but before the last
  line. Staff search every server's log for `mail <id> ` and, if `claimed_at` is set, give by hand only the rewards
  that have no `Gave` line on any server, checking the game state first where a reward's effect is visible (balance,
  inventory, the item itself). A reward whose `Gave` line is missing may still have run just before the crash. Never
  re-give all rewards and never reset `claimed_at` blindly.
- **Claims at shutdown and when the player leaves:** if the player left before the rewards were handed out, the claim
  is undone (`claimed_at` back to null, which sends `unclaimed`) so the mail shows again. On disable (players are
  still online then) a claim whose rewards are known but not yet handed out is handed out at once, or undone when the
  player is gone; a claim whose statement is still running is logged SEVERE with the mail id and uuid (it may have
  been claimed without its rewards); if it then claims after all, it is given back. A failed claim statement is
  logged SEVERE too, since it may still have committed, and so is a give-back that failed or lost its reply (it may
  have committed, and another server may have handed the mail out since). For all of these, staff follow the
  procedure above before touching `claimed_at`; a blind reset could reopen a redeemed mail.
- **One claim per mail at a time:** a second claim of the same mail waits until the first one has settled, also when
  the player reconnected meanwhile (the guard is per mail and lasts for the plugin's enable, not the session).
- **`expires_at` is ignored by the plugin**: expired mail that the sweep hasn't deleted yet can still be claimed.
- **Hooks kept for other plugins:** `NewMailEvent` (its `record` is a `MailRecord`) for new mail to an online
  recipient; the console cast `ms cast as <player> purchase-newmail` on new mail and on join with unread mail;
  PlaceholderAPI `%sneakymail_unread_count%`.
- **Commands:** `/mail` (`sneakymail.mail`) opens the GUI; `/mailcheck [id]` (`sneakymail.admin`) shows the cache.
- **Startup:** SneakyDB and SneakyItems are required. The plugin registers its migrations during enable and does
  nothing with its table until they are applied; when they fail, `/mail` says mail is not available.
- **Not shown until reload:** an edit by hand to an unclaimed mail (its note or rewards) sends no notification. Online
  players see it after they rejoin; claiming uses the rewards in the database, not the cached ones.

## Java API (`sneakymail-api`)

Consumers (lom-phone-app) compile only `io.github.team-sneakymouse:sneakymail-api:<version>`, never shade it, and
declare SneakyMail as a required Paper dependency with `join-classpath: true`. No database, Kotlin or Bukkit types
cross the contract.

```java
SneakyMailProvider.getApi().submitCommandMail(
    "phone:reward:<permanent-business-key>", recipientUuid,
    "Phone", "Your reward", "reward {player}");
```

- It calls `sneakymail.send(sourceKey, recipient, sender, note, [{"type":"command","command":command}], null)` and
  completes with `MailAcceptance(String.valueOf(mailId))` once that statement committed. Acceptance means the mail
  exists, not that it was claimed. Request ids are opaque; callers don't parse them.
- Retrying with the same key and arguments returns the same request id. Reusing a key with a different recipient,
  sender, note or command fails with `IllegalArgumentException("Source key already used with different payload")`.
- All five arguments must be non-null; a blank key, a key over 512 characters or a blank command fails. Failures
  come through the future, never as a synchronous exception. An exceptional result (also a 15-second timeout or the
  plugin stopping) may still have committed: retry with the exact same key and arguments.
- `SneakyMailProvider.getApi()` returns the installed service from enable on, even while `isAvailable()` is false
  (before the migrations finished, while SneakyDB is down, after disable). Only an uninstalled provider throws.

`./gradlew check apiJar generatePomFileForApiPublication` builds the plugin and the dependency-free API jar;
publishing the plugin publishes the API with the same commit-derived version first.

## Moving from PocketBase

`tools/import_pocketbase.py` copies `lom2_mail` into the empty table once, at the cutover; its docstring has the
rules (skipped expired lootbox mail, recipients by name, `ms magicitem` to item rewards, source keys from the old
journal) and how to run it. Run it with `--dry-run` first and read the report. It refuses to commit (also as the
dry run's verdict) when a journal-keyed row (its key is in the journal, wherever the key came from) differs from its
journal payload, when a record's own key and the journal pair differently, when two records would carry the same key
or a key is not 1 to 512 characters, when an unclaimed mail would still be invalid, or when journal keys would be lost
or journal rows are unconfirmed; the last two can be accepted with `--accept-lost-journal-keys` and
`--accept-unconfirmed-journal-rows`. Source keys are imported exactly as PocketBase has them (no trimming); only an
empty key means no key. The old journal table
`sneakymail.command_requests` stays until a later script drops it; the plugin no longer reads it.

Operator steps per database: the schema `sneakymail` exists already; for each role that sends mail (n8n's),
`grant usage on schema sneakymail to <role>;` and then
`grant execute on function sneakymail.send(text, uuid, text, text, jsonb, timestamptz) to <role>;`; schedule
`select sneakymail.delete_expired()` every 10 minutes as the plugin's role. A source key is remembered only while its
row exists, so never delete claimed mail rows (the sweep deletes only unclaimed, expired mail).

## Tests

- `./gradlew check`: unit tests (cache, claims helpers, notification and reward parsing, the API service) and the
  API jar checks.
- `SNEAKYMAIL_TEST_PG_URL=jdbc:postgresql://127.0.0.1:<port>/postgres?user=postgres&password=<pw> ./gradlew postgresTest`:
  V001 and the plugin's statements against a throwaway PostgreSQL (`docker run --rm -e POSTGRES_PASSWORD=<pw> -p
  127.0.0.1:<port>:5432 postgres:16`). The tests drop and recreate the `sneakymail` schema and create a role, so never
  point them at a shared database.
- `python3 -m unittest discover -s tools`: the import's conversion rules; with
  `SNEAKYMAIL_TEST_PG_DSN=<libpq conninfo of a throwaway database>` and psycopg (`uv run --with "psycopg[binary]"`)
  also its writes. Without the variable those tests are skipped.
