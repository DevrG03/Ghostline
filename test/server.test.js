'use strict';
// Starts the real server on a free port and talks to it over WebSockets.
const test = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const path = require('node:path');
const WebSocket = require('ws');

const PORT = 39000 + Math.floor(Math.random() * 1000);
const URL = `ws://127.0.0.1:${PORT}/ws`;
let proc;

test.before(async () => {
  proc = spawn(process.execPath, [path.join(__dirname, '..', 'server.js')], { env: { ...process.env, PORT: String(PORT) } });
  await new Promise((resolve) => proc.stdout.once('data', resolve));
});
test.after(() => proc.kill());

// A client that records every message, with a helper to wait for the next one of a type.
function client() {
  const ws = new WebSocket(URL);
  const inbox = [];
  const waiters = [];
  ws.on('message', (d) => {
    const m = JSON.parse(d);
    const i = waiters.findIndex((w) => w.type === m.type);
    if (i >= 0) waiters.splice(i, 1)[0].resolve(m); else inbox.push(m);
  });
  ws.on('error', () => {});
  return {
    ws,
    open: () => new Promise((r) => ws.once('open', r)),
    send: (m) => ws.send(JSON.stringify(m)),
    next: (type, ms = 2000) => {
      const i = inbox.findIndex((m) => m.type === type);
      if (i >= 0) return Promise.resolve(inbox.splice(i, 1)[0]);
      return new Promise((resolve, reject) => {
        waiters.push({ type, resolve });
        setTimeout(() => reject(new Error(`no ${type}`)), ms);
      });
    },
    closed: () => new Promise((r) => ws.once('close', r)),
  };
}
const room = (c) => c.repeat(64);

test('survives malformed and oversized input', async () => {
  for (const bad of ['null', '"x"', '5', 'x'.repeat(70000)]) {
    const c = client(); await c.open(); c.ws.send(bad); await c.closed();
  }
  const c = client(); await c.open(); c.ws.close();
});

test('one connection can be in several rooms; signals stay in their room', async () => {
  const a = client(), b = client(), c = client();
  await Promise.all([a.open(), b.open(), c.open()]);
  a.send({ type: 'join', room: room('1') }); a.send({ type: 'join', room: room('2') });
  assert.strictEqual((await a.next('joined')).peers, 0);
  await a.next('joined');
  b.send({ type: 'join', room: room('1') });
  c.send({ type: 'join', room: room('2') });
  assert.strictEqual((await b.next('joined')).peers, 1);
  assert.strictEqual((await c.next('joined')).peers, 1);
  const pj = [await a.next('peer-joined'), await a.next('peer-joined')].map((m) => m.room).sort();
  assert.deepStrictEqual(pj, [room('1'), room('2')]);

  a.send({ type: 'signal', room: room('2'), data: 'for-c' });
  const s = await c.next('signal');
  assert.deepStrictEqual([s.room, s.data], [room('2'), 'for-c']);
  await assert.rejects(b.next('signal', 300)); // b is only in room 1

  // a third connection can't join a full room
  const d = client(); await d.open();
  d.send({ type: 'join', room: room('1') });
  assert.strictEqual((await d.next('full')).room, room('1'));

  // leaving one room tells only that room's peer
  a.send({ type: 'leave', room: room('1') });
  assert.strictEqual((await b.next('peer-left')).room, room('1'));
  await assert.rejects(c.next('peer-left', 300));
  for (const x of [a, b, c, d]) x.ws.close();
});

test('pairing: create, join, relay, end', async () => {
  const a = client(), b = client();
  await Promise.all([a.open(), b.open()]);
  a.send({ type: 'pair-create' });
  const { slot } = await a.next('pair-created');
  assert.ok(slot >= 10 && slot < 100);
  b.send({ type: 'pair-join', slot: String(slot) });
  await a.next('pair-peer'); await b.next('pair-peer');
  a.send({ type: 'pair-msg', data: 'pA' });
  assert.strictEqual((await b.next('pair-msg')).data, 'pA');
  b.send({ type: 'pair-msg', data: 'pB' });
  assert.strictEqual((await a.next('pair-msg')).data, 'pB');

  // the slot can't be claimed twice
  const c = client(); await c.open();
  c.send({ type: 'pair-join', slot });
  assert.strictEqual((await c.next('pair-ended')).reason, 'no-such-code');

  b.send({ type: 'pair-end', reason: 'done' });
  assert.strictEqual((await a.next('pair-ended')).reason, 'done');
  c.send({ type: 'pair-join', slot });
  assert.strictEqual((await c.next('pair-ended')).reason, 'no-such-code');
  for (const x of [a, b, c]) x.ws.close();
});

test('pairing: creator leaving ends it for the joiner', async () => {
  const a = client(), b = client();
  await Promise.all([a.open(), b.open()]);
  a.send({ type: 'pair-create' });
  const { slot } = await a.next('pair-created');
  b.send({ type: 'pair-join', slot });
  await b.next('pair-peer');
  a.ws.close();
  assert.strictEqual((await b.next('pair-ended')).reason, 'peer-gone');
  b.ws.close();
});

test('pairing: limits code attempts per address', async () => {
  const c = client(); await c.open();
  const reasons = [];
  for (let i = 0; i < 12; i++) {
    c.send({ type: 'pair-join', slot: 5 }); // never a valid slot
    reasons.push((await c.next('pair-ended')).reason);
  }
  assert.ok(reasons.includes('too-many-attempts'));
  c.ws.close();
});
