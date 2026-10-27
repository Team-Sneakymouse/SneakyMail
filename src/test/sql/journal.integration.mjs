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
 await check('first claim persists attempted marker before transport', async () => {
  const row = (await db.query(claim, ['lease1'])).rows[0];
  assert.equal(row.was_attempted, false);
  assert.equal(row.request_id, 'abc123abc123abc');
  assert.equal(typeof row.payload, 'string');
  assert.notEqual((await db.query('SELECT attempted_at FROM sneakymail.command_requests')).rows[0].attempted_at, null);
  assert.equal((await db.query(claim, ['lease2'])).rows.length, 0);
 });
 await check('fresh work outranks uncertain oldest row', async () => {
  await db.exec("UPDATE sneakymail.command_requests SET lease_until=now()-interval '1 second'");
  await insert('two', 'def123def123def');
  const row = (await db.query(claim, ['lease2'])).rows[0];
  assert.equal(row.request_id, 'def123def123def'); assert.equal(row.was_attempted, false);
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
 console.log(`${tests} SQL tests passed; 0 skipped. Independent-session locking not verified by WASM.`);
} finally { await db.close(); }
