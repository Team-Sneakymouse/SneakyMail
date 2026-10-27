# Durable command-mail API

Consumers compile **only** `io.github.team-sneakymouse:sneakymail-api:<genuine published commit version>` and declare SneakyMail as a required Paper server dependency with `join-classpath: true`. Never shade this artifact: the provider registry must exist exactly once, in SneakyMail's runtime classloader. No PocketBase, SneakyDB, Kotlin or Bukkit types cross the contract.

```java
SneakyMailProvider.getApi().submitCommandMail(
    "phone:reward:<permanent-business-key>", recipientUuid,
    "Phone", "Your reward", "reward {player}");
```

The future returns `MailAcceptance(requestId)` only after a durable SQL statement completes. This is **accepted responsibility**, not delivered, available, claimed, or command executed. An exceptional result (including timeout/shutdown) can be an uncertain commit: retry the exact same key and full payload. Reuse with changed UUID, sender, note or command fails; no trimming or command rewriting occurs. Request IDs are opaque permanent acceptance references; callers must not infer a storage format or treat them as mail-row IDs. The current implementation internally uses a 15-character identifier compatible with PocketBase. Protect source keys with a caller-specific namespace. Trusted in-process callers decide rewards and commands; this is not an untrusted HTTP endpoint.

## Operator installation / cutover gates

1. Deploy compatible SneakyDB (`sneakydb` pinned to genuine `2026.10.24-4a58c0cac70a`) and SneakyPocketbase (`sneakypocketbase-api` pinned to `2026.10.09-70effb404392`) managers. Only SneakyMail knows the delivery manager and `lom2_mail` storage format; consumers must not.
2. As database owner, review and execute [command-mail-journal.sql](command-mail-journal.sql) in the database configured by SneakyDB. This creates SneakyMail's own `sneakymail.command_requests` schema/table; no application auto-DDL or live migration is performed.
3. Replace the commented grant role with the actual manager database role; grant schema USAGE and table SELECT/INSERT/UPDATE. No sequence grant is needed. Never grant or use DELETE/TRUNCATE on the journal. Preserve rows and confirmed receipts permanently, including after PocketBase mail deletion. Back up the journal.
4. SneakyPocketbase must have permission for exact-ID GET and custom-ID CREATE of `lom2_mail`. Existing PBListener continues consuming created records; UI, legacy `MailSender` callers and redemption remain unchanged.
5. Retire the old phone Node delivery worker before switching. This phone app is a milestone installation with no production-history migration: stop writers and recreate its disposable test state if needed. Do not run two delivery implementations for one retained business intent. Do not drop shared PocketBase mail or other callers' data as part of a phone reset.
6. Deploy matching plugin runtime and publish the skinny API before pinning consumers. Build version is generated from the actual Git HEAD; **do not publish these uncommitted changes under the current base commit's version**. Parent must commit, rebuild and use that genuine commit-derived version. No release-workflow changes are included.

## Delivery / recovery semantics

A single SQL CTE performs idempotent insert and full JSONB equality matching under `ON CONFLICT`; competing submissions cannot reserve different receipts for the same key. The manager has no transaction API, so there are no application-managed multi-statement transactions.

An asynchronous worker claims a row with `FOR UPDATE SKIP LOCKED`, persisting `attempted_at`, stable request ID, rotating check time, and a 60-second lease **before** calling PocketBase. Never-attempted work sorts ahead of uncertain rows; uncertain rows rotate by last check. A normal worker pumps once per second with one in-flight operation. Queries and delivery stages each have 15-second deadlines. PocketBase API availability and generation fence new sends and callback receipt writes; shutdown uninstalls only the owning provider, fails outstanding admission futures, and stops scheduling without a main-thread join.

Only the first durable claim may POST. After any uncertainty (timeout, response loss, generation change, failure, process crash), every later claim is exact-ID **GET only**. A 404 can mean already claimed/deleted mail and **never authorizes another POST**. A successful create or exact-ID read records permanent `confirmed_at`; confirmed rows never enter the queue again. A crash after the attempted marker but before POST can therefore strand a reward safely rather than duplicate it. Lease expiry does not restore POST permission.

Inspect unconfirmed rows and `attempted_at`/`last_checked` during operations. Failures are logged at most once per minute without payloads. Missing uncertain records require audited manual investigation. Never reset attempted markers or delete journal rows to force retries. There is intentionally no automatic destructive repair or resend command. A PocketBase delivery receipt is not proof of exactly-once external command redemption; the existing redemption system has its own independent failure window.

## Build / publication

`./gradlew check apiJar generatePomFileForApiPublication` builds the runtime plugin and separate dependency-free API JAR. `publishApiPublicationToSneakyrpRepository` publishes the API with the same genuine commit-derived version. The existing runtime publication task now depends on API publication, so the unchanged release workflow publishes both matching artifacts. API POM must not contain `sneakyrp.deployment.type=paper-plugin`. Publish SneakyMail first, verify the actual API release version, and pin consumers to that version. Consumer compilation may use a locally published genuine API while the release is pending; that is not proof of remote availability.

Unit tests inject actual manager interfaces; manager mocks are boundaries, not fake replacement public APIs. The actual journal SQL/DDL also passes seven PGlite checks, plus three real PostgreSQL 18.4 multi-session scenarios (duplicate admission/mismatch rejection, SKIP LOCKED claims, stable-ID recovery/stale-owner confirmation). Reproduce the latter with `MAIL_PG_TEST_DEPENDENCIES=/path/to/installed/embedded-postgres-and-pg node src/test/sql/journal.postgres.mjs`; it starts its own disposable loopback cluster and does not use any existing DATABASE_URL. Live plugin loading, actual manager permissions/realtime delivery and in-game claim behavior remain deployment checks.
