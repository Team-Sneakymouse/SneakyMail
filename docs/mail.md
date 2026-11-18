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
| `sneakymail.send(source_key, recipient, sender_name, note, rewards, expires_at default null)` | Inserts and returns the id. With a source key it is idempotent: the same key with the same recipient, sender, note, rewards and expiry returns the first mail's id, also for concurrent calls; different content raises SQLSTATE `23505`. A null note becomes `''`. `security definer`; EXECUTE is revoked from PUBLIC, an operator grants it |
| `sneakymail.delete_expired()` | Deletes unclaimed mail whose `expires_at` has passed and returns the count. pg_cron runs it every 10 minutes as the plugin's role (scheduled by an operator) |
| trigger `mail_notify` | `pg_notify('sneakymail', ...)` with `{"event":"new","id":<id>,"recipient":"<uuid>"}` after an insert, `"claimed"` when `claimed_at` goes from null to a value, `"deleted"` after a delete |

Rewards:

- `command` runs as the console with `{player}` replaced by the recipient's name. Only trusted senders write commands;
  the note is display text (MiniMessage) and never becomes command input.
- `item` gives `amount` copies of a SneakyItems magic item by internal name. MagicSpells registers its magic items in
  SneakyItems under their `magic-items:` keys, so `ms magicitem item-EXP {player}` and
  `{"type":"item","item":"item-EXP","amount":1}` give the same item.

Sending by hand (DBeaver): `select sneakymail.send(null, '<uuid>', '<gold>Staff</gold>', 'Thanks!', '[{"type":"item","item":"item-EXP","amount":5}]');`
or a plain insert. Either shows up in game at once for an online recipient.

## The plugin

- **Cache:** only online players' unclaimed mail, loaded when they join and dropped when they quit (`MailCache`). The
  subscription to channel `sneakymail` reloads a player on `new` and drops a mail on `claimed` and `deleted`; when it
  (re)connects, every online player is reloaded, because notifications sent while it was down are lost.
  Notification and database callbacks run off the server thread and are rescheduled onto it.
- **Claiming** (`MailClaims`): on the server thread SneakyMail checks that SneakyItems knows every item reward and
  that the player's inventory has room for all of them; otherwise the mail stays and the player is told to tell staff
  or to make room. Then, off the server thread, the claim statement:

  ```sql
  update sneakymail.mail set claimed_at = now() where id = ? and recipient_uuid = ? and claimed_at is null returning rewards
  ```

  Rewards are handed out only when a row comes back ("already collected" otherwise), in their order, on the server
  thread. Items that no longer fit drop at the player's feet. Each reward is logged (`Redeeming <player>'s mail <id>
  with command: ...` / `with item: ...`). If the player left or the plugin stopped before the rewards could be
  handed out, the claim is undone so the mail shows again at the next join.
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
journal) and how to run it. Run it with `--dry-run` first and read the report. The old journal table
`sneakymail.command_requests` stays until a later script drops it; the plugin no longer reads it.

Operator steps per database: the schema `sneakymail` exists already; grant EXECUTE on `sneakymail.send` to the
roles that send mail (n8n's); schedule `select sneakymail.delete_expired()` every 10 minutes as the plugin's role.

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
