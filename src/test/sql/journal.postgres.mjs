// Starts a disposable loopback PostgreSQL cluster; never uses an existing DATABASE_URL.
// MAIL_PG_TEST_DEPENDENCIES=/path/to/test-deps node src/test/sql/journal.postgres.mjs
// test-deps must contain embedded-postgres and pg (not production dependencies).
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { randomBytes } from 'node:crypto';
import net from 'node:net';
const dependencyRoot = process.env.MAIL_PG_TEST_DEPENDENCIES;
assert.ok(dependencyRoot, 'Set MAIL_PG_TEST_DEPENDENCIES');
const require = createRequire(resolve(dependencyRoot, 'package.json'));
const { default: EmbeddedPostgres } = await import(require.resolve('embedded-postgres'));
const { Client } = require('pg');
const scratch = mkdtempSync(join(tmpdir(), 'mail-journal-pg-'));
const server = net.createServer();
await new Promise(r => server.listen(0, '127.0.0.1', r));
const port = server.address().port;
await new Promise(r => server.close(r));
const password = randomBytes(24).toString('hex');
const cluster = new EmbeddedPostgres({ databaseDir: join(scratch, 'data'), user: 'mail_test', password,
 port, persistent: false, createPostgresUser: false, postgresFlags: ['-h', '127.0.0.1', '-k', scratch], onLog() {}, onError: console.error });
const clients = [];
function sql(file, name) {
 const source = readFileSync(`src/main/kotlin/com/danidipp/sneakymail/${file}.kt`, 'utf8');
 const match = source.match(new RegExp(`const val ${name} = """([\\s\\S]*?)"""`));
 assert.ok(match, name);
 let n = 0; return match[1].replaceAll('?', () => `$${++n}`);
}
try {
 await cluster.initialise(); await cluster.start();
 const options = { host: '127.0.0.1', port, user: 'mail_test', password, database: 'postgres', statement_timeout: 10000 };
 for (let i = 0; i < 3; i++) { const c = new Client(options); await c.connect(); clients.push(c); }
 const [admin, a, b] = clients;
 await admin.query(readFileSync('docs/command-mail-journal.sql', 'utf8'));
 const submit = sql('DurableMailService', 'SUBMIT');
 const claim = sql('MailDeliveryWorker', 'CLAIM');
 const confirm = sql('MailDeliveryWorker', 'CONFIRM');
 const payload = JSON.stringify({ recipient_uuid: '00000000-0000-0000-0000-000000000001', sender_name: 'Phone', note: 'note', command: 'reward' });
 const duplicates = await Promise.all([
  a.query(submit, ['one', 'abc123abc123abc', payload]),
  b.query(submit, ['one', 'def123def123def', payload]),
 ]);
 assert.equal(duplicates[0].rows[0].request_id, duplicates[1].rows[0].request_id);
 const id = duplicates[0].rows[0].request_id;
 assert.equal((await admin.query('SELECT count(*)::int n FROM sneakymail.command_requests')).rows[0].n, 1);
 assert.equal((await b.query(submit, ['one', 'ghi123ghi123ghi', JSON.stringify({ recipient_uuid: 'other' })])).rows.length, 0);
 console.log('PASS: independent submissions share one durable acceptance; changed payload is rejected');
 await a.query('BEGIN');
 const first = (await a.query(claim, ['owner-a'])).rows[0];
 assert.equal(first.was_attempted, false);
 assert.equal((await b.query(claim, ['owner-b'])).rows.length, 0);
 await a.query('COMMIT');
 assert.equal((await b.query(claim, ['owner-b'])).rows.length, 0);
 console.log('PASS: SKIP LOCKED skips an uncommitted claim; committed lease prevents another send owner');
 await admin.query("UPDATE sneakymail.command_requests SET lease_until=now()-interval '1 second'");
 const recovered = (await b.query(claim, ['owner-b'])).rows[0];
 assert.equal(recovered.was_attempted, true);
 assert.equal(recovered.request_id, id);
 assert.equal((await a.query(confirm, [id, 'owner-a'])).rowCount, 0);
 assert.equal((await b.query(confirm, [id, 'owner-b'])).rowCount, 1);
 assert.equal((await a.query(submit, ['one', 'jkl123jkl123jkl', payload])).rows[0].request_id, id);
 assert.equal((await a.query(claim, ['owner-c'])).rows.length, 0);
 console.log('PASS: recovery keeps ID and is GET-only; old owner cannot confirm; receipt survives resubmission');
 console.log((await admin.query('SELECT version()')).rows[0].version);
} finally {
 for (const c of clients) { await c.query('ROLLBACK').catch(() => {}); await c.end(); }
 await cluster.stop();
}
