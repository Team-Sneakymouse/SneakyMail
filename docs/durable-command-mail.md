# Durable command-mail API

Consumers compile **only** `io.github.team-sneakymouse:sneakymail-api:<genuine published commit version>` and declare SneakyMail as a required Paper server dependency with `join-classpath: true`. Never shade this artifact: the provider registry must exist exactly once, in SneakyMail's runtime classloader. No PocketBase, SneakyDB, Kotlin or Bukkit types cross the contract.

```java
SneakyMailProvider.getApi().submitCommandMail(
    "phone:reward:<permanent-business-key>", recipientUuid,
    "Phone", "Your reward", "reward {player}");
```

The future returns `MailAcceptance(requestId)` only after a durable SQL statement completes. This is **accepted responsibility**, not delivered, available, claimed, or command executed. An exceptional result (including timeout/shutdown) can be an uncertain commit: retry the exact same key and full payload. Reuse with changed UUID, sender, note or command fails; no trimming or command rewriting occurs. Request IDs are opaque permanent acceptance references; callers must not infer a storage format or treat them as mail-row IDs. The current implementation internally uses a 15-character identifier compatible with PocketBase. Protect source keys with a caller-specific namespace. Trusted in-process callers decide rewards and commands; this is not an untrusted HTTP endpoint.

`SneakyMailProvider.getApi()` returns an installed service even when `isAvailable()` is false. Only an absent/uninstalled provider throws. Submission while unavailable or stopped returns a failed future. All five arguments must be non-null; Java null arguments also return failed futures without synchronous Kotlin null-check exceptions.

## Operator installation / cutover gates

PocketBase-only installations retain legacy commands, listeners, UI and `MailSender` without SneakyDB. SneakyDB is an optional joined-classpath dependency: the SQL-linked bootstrap and durable API/runtime are activated only when that plugin is enabled. No durable provider is installed in the legacy-only branch. The real enable ordering is exercised through injectable legacy/provider boundaries; this is not a full Paper server bootstrap test.

For **durable API** installations:

1. Deploy compatible SneakyDB (`sneakydb` pinned to genuine `2026.10.24-4a58c0cac70a`) and SneakyPocketbase (`sneakypocketbase-api` pinned to published `2026.10.28-1792010c7b91`, including typed create outcomes) managers. Only SneakyMail knows the delivery manager and `lom2_mail` storage format; consumers must not.
2. As database owner, review and execute [command-mail-journal.sql](command-mail-journal.sql) in the database configured by SneakyDB. This creates SneakyMail's own `sneakymail.command_requests` schema/table; no application auto-DDL or live migration is performed.
3. Replace the commented grant role with the actual manager database role; grant schema USAGE and table SELECT/INSERT/UPDATE. No sequence grant is needed. Never grant or use DELETE/TRUNCATE on the journal. Preserve rows and confirmed receipts permanently, including after PocketBase mail deletion. Back up the journal.
4. SneakyPocketbase must have permission for exact-ID GET and custom-ID CREATE of `lom2_mail`. Existing PBListener continues consuming created records; UI, legacy `MailSender` callers and redemption remain unchanged.
5. Retire the old phone Node delivery worker before switching. This phone app is a milestone installation with no production-history migration: stop writers and recreate its disposable test state if needed. Do not run two delivery implementations for one retained business intent. Do not drop shared PocketBase mail or other callers' data as part of a phone reset.
6. Deploy matching plugin runtime and publish the skinny API before pinning consumers. Build version is generated from the actual Git HEAD; **do not publish these uncommitted changes under the current base commit's version**. Parent must commit, rebuild and use that genuine commit-derived version. No release-workflow changes are included.

## Delivery / recovery semantics

A single SQL CTE performs idempotent insert and full JSONB equality matching under `ON CONFLICT`; competing submissions cannot reserve different receipts for the same key. The manager has no transaction API, so there are no application-managed multi-statement transactions.

The asynchronous protocol separates three operations:

1. `CLAIM` leases one row with `FOR UPDATE SKIP LOCKED`, updates `last_checked`, and sets a 60-second lease. It **does not** mark a send. A lost/timed-out claim callback, reload or shutdown before marking leaves never-attempted work retryable after lease expiry.
2. Validate and serialize the payload, then `MARK` persists `attempted_at` and an independent `attempt_token`, fenced by the current, unexpired `lease_token`. Only a successful marker permits POST. Immediately before calling create, recheck lifecycle generation, shutdown and the observer deadline.
3. A valid create/GET response with the exact ID permits a lease-fenced permanent `confirmed_at` receipt. A receipt-write failure leaves the intent attempted; recovery is GET-only.

The worker observes original manager futures without completing, cancelling or applying `orTimeout` to them. Its independent 15-second operation observer and explicit elapsed-time checks fence late callbacks. A late successful marker commit after an observer timeout/reload/close is still handled: if create was never invoked, reset only that `attempt_token`, with `confirmed_at IS NULL`. Lease changes do not prevent this compensation; an older compensation cannot clear a newer attempt or confirmed receipt. Manager methods are asynchronous boundaries; invocation of create marks possible HTTP delivery, not proof that HTTP actually went out.

Never-attempted work sorts ahead of uncertain rows; uncertain rows rotate by last check. `pump()` retains single-request behavior. Runtime `pumpBatch()` drains up to 32 requests, with a 1-second between-request time budget and one in-flight operation. Its arguments configure the request cap/time budget; an already-started async request may run until the operation observer deadline. Empty queues, errors, shutdown, changed manager generation, request cap or elapsed batch budget stop the drain. The SQL index matches `last_checked NULLS FIRST` so the claim can use an ordered scan without Sort.

PocketBase availability and generation fence sends and receipt callbacks. Shutdown uninstalls only its provider, fails admission futures and stops scheduling without a main-thread join.

After create invocation, every unclassified failure/timeout/response gap is possible delivery: every later claim is exact-ID **GET only**. A 404 can mean already claimed/deleted mail and **never authorizes another POST**. Confirmed rows never enter the queue again. A process crash between marker commit and HTTP can still strand a reward safely rather than duplicate it; that irreducible uncertainty requires audited manual investigation. Lease expiry alone never restores POST permission.

The internal classifier seam is `MailDeliveryWorker(database, pocketbase, timeoutMillis, Predicate<Throwable> definitelyNotSent)`. It receives the unwrapped create failure. Its default recognizes only the public `PocketbaseWriteFailure` outcomes `NOT_SENT` and `REJECTED`; `UNKNOWN` and all untyped failures remain uncertain. The build pins published SneakyPocketbase API `2026.10.28-1792010c7b91`; deploy the matching manager runtime before enabling this worker. Older API/runtime versions do not contain this contract. A trustworthy `NOT_SENT`/`REJECTED` classification permits resetting the exact attempt marker, also for a late failure after the local observer deadline. This sets `next_attempt_at = now() + 5 seconds`; the existing lease must also expire, so automatic retry is bounded by the lease rather than a tight rejection loop. Ordinary IOExceptions, generic errors and 404s are never sufficient evidence. Invalid never-attempted payloads are quarantined (`quarantined_at`) before marking, not put into an endless GET loop.

Inspect unconfirmed rows and `attempted_at`, `attempt_token`, `last_checked`, `next_attempt_at` and `quarantined_at` during operations. Warnings include stage, opaque request ID (or `unclaimed`) and unwrapped exception category only: never raw provider messages, stacks, source keys or payloads. The first warning is unconditional even at an arbitrary negative monotonic-clock origin; further warnings are throttled to once per minute. Quarantined payloads require operator investigation; admission identity and payload cannot be edited in place. Missing uncertain records or failed marker compensation require audited manual investigation. Never reset attempted markers or delete journal rows to force retries. There is intentionally no automatic destructive repair or resend command. A PocketBase delivery receipt is not proof of exactly-once external command redemption; the existing redemption system has its own independent failure window.

### Upgrading an earlier review schema

This DDL is an operator-reviewed fresh-install script, not an automatic migration. An existing review schema needs nullable `attempt_token text`, `next_attempt_at timestamptz` and `quarantined_at timestamptz`, the immutable-identity/receipt trigger from the script, and recreation of `command_requests_pending` with `last_checked NULLS FIRST`. Stop all workers before changing the protocol. Previously marked rows without an attempt token remain GET-only; do not infer they were unsent or clear their markers. Preserve all keys, request IDs, payloads and confirmed receipts. No live SQL has been executed by this change.

## Build / publication

`./gradlew check apiJar generatePomFileForApiPublication` builds the runtime plugin and separate dependency-free API JAR. `publishApiPublicationToSneakyrpRepository` publishes the API with the same genuine commit-derived version. The existing runtime publication task now depends on API publication, so the unchanged release workflow publishes both matching artifacts. API POM must not contain `sneakyrp.deployment.type=paper-plugin`. Publish SneakyMail first, verify the actual API release version, and pin consumers to that version. Consumer compilation may use a locally published genuine API while the release is pending; that is not proof of remote availability.

Unit tests inject actual manager interfaces; manager mocks are boundaries, not fake replacement public APIs. The actual journal SQL/DDL is exercised by ten PGlite checks and five real PostgreSQL 18.4 scenarios, covering admission identity, lease-only claims, marker/reset fencing across owners, permanent receipts and the ordered index EXPLAIN plan. Reproduce the latter with `MAIL_PG_TEST_DEPENDENCIES=/path/to/installed/embedded-postgres-and-pg node src/test/sql/journal.postgres.mjs`; it starts its own disposable loopback cluster and does not use any existing DATABASE_URL. Live plugin loading, actual manager permissions/realtime delivery and in-game claim behavior remain deployment checks.
