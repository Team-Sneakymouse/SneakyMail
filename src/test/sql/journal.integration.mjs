// Runs actual production SQL against PostgreSQL (PGlite WASM, single-session only).
// node src/test/sql/journal.integration.mjs /absolute/path/to/@electric-sql/pglite/dist/index.js
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
const { PGlite } = await import(pathToFileURL(process.argv[2]).href);
const db = new PGlite();
const sqlFrom = (file, name) => {
 const source = fs.readFileSync(file, 'utf8');
 const raw = source.match(new RegExp(`const val ${name} = """([\\s\\S]*?)"""`))[1];
 let n = 0; return raw.replaceAll('?', () => `$${++n}`);
};
const base = 'src/main/kotlin/com/danidipp/sneakymail/';
const submit = sqlFrom(base + 'DurableMailService.kt', 'SUBMIT');
const claim = sqlFrom(base + 'MailDeliveryWorker.kt', 'CLAIM');
const confirm = sqlFrom(base + 'MailDeliveryWorker.kt', 'CONFIRM');
const mark = sqlFrom(base + 'MailDeliveryWorker.kt', 'MARK');
const reset = sqlFrom(base + 'MailDeliveryWorker.kt', 'RESET');
let tests = 0;
const check = async (name, fn) => { await fn(); tests++; console.log(`PASS ${name}`); };
const payload = { recipient_uuid: '00000000-0000-0000-0000-000000000001', sender_name: 'Phone', note: 'note', command: 'reward' };
const insert = (key, id, value = payload) => db.query(submit, [key, id, JSON.stringify(value)]);
try {
 await db.exec(fs.readFileSync('docs/command-mail-journal.sql', 'utf8'));
 await check('durable duplicate returns original stable ID after restart-equivalent resubmit', async () => {
  assert.equal((await insert('one', 'abc123abc123abc')).rows[0].request_id, 'abc123abc123abc');
  assert.equal((await insert('one', 'def123def123def')).rows[0].request_id, 'abc123abc123abc');
 });
 await check('every canonical payload field participates in conflict matching', async () => {
  for (const key of Object.keys(payload)) assert.equal((await insert('one', 'def123def123def', { ...payload, [key]: 'different' })).rows.length, 0);
 });
 await check('JSON key order is not payload identity', async () => {
  assert.equal((await insert('one', 'def123def123def', Object.fromEntries(Object.entries(payload).reverse()))).rows[0].request_id, 'abc123abc123abc');
 });
 await check('lease alone stays retryable and fenced mark precedes transport', async () => {
  const row = (await db.query(claim, ['lease1'])).rows[0];
  assert.equal(row.was_attempted, false);
  assert.equal(row.request_id, 'abc123abc123abc');
  assert.equal(typeof row.payload, 'string');
  assert.equal((await db.query('SELECT attempted_at FROM sneakymail.command_requests')).rows[0].attempted_at, null);
  assert.equal((await db.query(mark, ['attempt1', row.request_id, 'stale'])).affectedRows, 0);
  assert.equal((await db.query(mark, ['attempt1', row.request_id, 'lease1'])).affectedRows, 1);
  assert.equal((await db.query(claim, ['lease2'])).rows.length, 0);
 });
 await check('fresh work outranks uncertain oldest row', async () => {
  await db.exec("UPDATE sneakymail.command_requests SET lease_until=now()-interval '1 second'");
  await insert('two', 'def123def123def');
  const row = (await db.query(claim, ['lease2'])).rows[0];
  assert.equal(row.request_id, 'def123def123def'); assert.equal(row.was_attempted, false);
  assert.equal((await db.query(mark, ['attempt2', row.request_id, 'lease2'])).affectedRows, 1);
 });
 await check('uncertain recovery rotates instead of starving other uncertainty', async () => {
  await db.exec("UPDATE sneakymail.command_requests SET lease_until=now()-interval '1 second'");
  assert.equal((await db.query(claim, ['lease3'])).rows[0].request_id, 'abc123abc123abc');
  await db.exec("UPDATE sneakymail.command_requests SET lease_until=now()-interval '1 second'");
  const row = (await db.query(claim, ['lease4'])).rows[0];
  assert.equal(row.request_id, 'def123def123def'); assert.equal(row.was_attempted, true);
 });
 await check('stale lease cannot confirm and successful receipts are permanent', async () => {
  assert.equal((await db.query(confirm, ['def123def123def', 'lease2'])).affectedRows, 0);
  assert.equal((await db.query(confirm, ['def123def123def', 'lease4'])).affectedRows, 1);
  await db.exec("UPDATE sneakymail.command_requests SET lease_until=now()-interval '1 second'");
  assert.equal((await db.query(claim, ['lease5'])).rows[0].request_id, 'abc123abc123abc');
  assert.equal((await insert('two', 'ghi123ghi123ghi')).rows[0].request_id, 'def123def123def');
 });
 await check('attempt reset ignores changed lease but cannot clear newer attempt or confirmed receipt', async () => {
  const id = 'abc123abc123abc';
  assert.equal((await db.query(reset, [id, 'stale-attempt'])).affectedRows, 0);
  assert.equal((await db.query(reset, [id, 'attempt1'])).affectedRows, 1);
  await db.exec("UPDATE sneakymail.command_requests SET next_attempt_at=now()-interval '1 second', lease_until=now()-interval '1 second'");
  const row = (await db.query(claim, ['lease6'])).rows[0]; assert.equal(row.was_attempted, false);
  assert.equal((await db.query(mark, ['new-attempt', id, 'lease6'])).affectedRows, 1);
  assert.equal((await db.query(reset, [id, 'attempt1'])).affectedRows, 0);
  assert.equal((await db.query(confirm, [id, 'lease6'])).affectedRows, 1);
  assert.equal((await db.query(reset, [id, 'new-attempt'])).affectedRows, 0);
 });
 await check('keys payload request ID and confirmed receipt are immutable', async () => {
  for (const assignment of ["source_key='changed'", "request_id='xxx123xxx123xxx'", "payload='{}'::jsonb", 'confirmed_at=NULL']) {
   await assert.rejects(db.exec(`UPDATE sneakymail.command_requests SET ${assignment} WHERE source_key='one'`));
  }
 });
 await check('claim index matches NULLS FIRST production ordering without sort', async () => {
  await db.exec(`INSERT INTO sneakymail.command_requests(source_key,request_id,payload)
   SELECT 'plan:'||i, lpad(i::text,15,'0'), '{}'::jsonb FROM generate_series(1,10000) i;
   ANALYZE sneakymail.command_requests`);
  const plan = JSON.stringify((await db.query('EXPLAIN (FORMAT JSON) ' + claim, ['plan'])).rows);
  const nodeTypes = [...plan.matchAll(/Node Type[^A-Za-z]+([A-Za-z ]+)/g)].map(x => x[1]);
  assert.ok(plan.includes('command_requests_pending')); assert.ok(!nodeTypes.some(x => x.includes('Sort')), plan);
 });
 console.log(`${tests} SQL tests passed; 0 skipped. Independent-session locking not verified by WASM.`);
} finally { await db.close(); }
