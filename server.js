'use strict';

// Ghostline server: serves three static files and relays *encrypted* signaling
// blobs between the two people in a room. It stores nothing, logs nothing,
// and cannot read messages, calls or even the room key.

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = Number(process.env.PORT) || 3000;
const HOST = process.env.HOST || '127.0.0.1';

// Abuse limits. Signaling is tiny, so these are generous for real use.
const MAX_CLIENTS = Number(process.env.MAX_CLIENTS) || 500;
const RATE_WINDOW_MS = 10_000;
const RATE_MAX_MESSAGES = 300;
const MAX_ROOMS_PER_CLIENT = 100;
const PAIR_TTL_MS = 10 * 60 * 1000;
const PAIR_ATTEMPTS_PER_IP = 10; // pairing-code attempts per address per 10 minutes

// ---------- ICE (STUN/TURN) configuration ----------
// STUN_URLS:   comma-separated, default Google's public STUN. Set to "" to use none.
// TURN_URLS:   comma-separated TURN urls (e.g. "turns:turn.example.com:5349").
// TURN_SECRET: coturn "static-auth-secret"; short-lived credentials are minted per request.
//              (Or TURN_USERNAME + TURN_CREDENTIAL for fixed credentials.)
// RELAY_ONLY:  defaults to on when TURN is configured, which hides IPs; "0" turns it off.

const list = (v) => (v || '').split(',').map((s) => s.trim()).filter(Boolean);
const STUN_URLS = process.env.STUN_URLS === undefined
  ? ['stun:stun.l.google.com:19302']
  : list(process.env.STUN_URLS);
const TURN_URLS = list(process.env.TURN_URLS);
const TURN_SECRET = process.env.TURN_SECRET;
const TURN_TTL_S = 12 * 3600;
const RELAY_ONLY = TURN_URLS.length > 0 && process.env.RELAY_ONLY !== '0';

if (TURN_URLS.length && !TURN_SECRET && !(process.env.TURN_USERNAME && process.env.TURN_CREDENTIAL)) {
  console.warn('TURN_URLS is set but neither TURN_SECRET nor TURN_USERNAME/TURN_CREDENTIAL is.');
}

function iceConfig() {
  const iceServers = [];
  // In relay-only mode STUN is never needed, so don't contact a third party at all.
  if (STUN_URLS.length && !RELAY_ONLY) iceServers.push({ urls: STUN_URLS });
  if (TURN_URLS.length) {
    let username = process.env.TURN_USERNAME;
    let credential = process.env.TURN_CREDENTIAL;
    if (TURN_SECRET) {
      // coturn "use-auth-secret" (TURN REST API) credentials that expire on their own.
      username = `${Math.floor(Date.now() / 1000) + TURN_TTL_S}:ghostline`;
      credential = crypto.createHmac('sha1', TURN_SECRET).update(username).digest('base64');
    }
    iceServers.push({ urls: TURN_URLS, username, credential });
  }
  return { iceServers, relayOnly: RELAY_ONLY };
}

// ---------- HTTP ----------

const FILES = {
  '/': ['index.html', 'text/html; charset=utf-8'],
  '/app.js': ['app.js', 'text/javascript; charset=utf-8'],
  '/style.css': ['style.css', 'text/css; charset=utf-8'],
  '/spake2.js': ['spake2.js', 'text/javascript; charset=utf-8'],
};
const STATIC = Object.fromEntries(
  Object.entries(FILES).map(([url, [file, type]]) => [
    url,
    { type, body: fs.readFileSync(path.join(__dirname, 'public', file)) },
  ])
);

const SECURITY_HEADERS = {
  'Content-Security-Policy': [
    "default-src 'none'",
    "script-src 'self'",
    "style-src 'self'",
    "connect-src 'self'",
    "media-src 'self' blob: mediastream:",
    "img-src 'self' data:",
    "base-uri 'none'",
    "form-action 'none'",
    "frame-ancestors 'none'",
    "require-trusted-types-for 'script'",
  ].join('; '),
  'Referrer-Policy': 'no-referrer',
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'X-DNS-Prefetch-Control': 'off',
  'Cache-Control': 'no-store, max-age=0',
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Cross-Origin-Embedder-Policy': 'require-corp',
  'Cross-Origin-Resource-Policy': 'same-origin',
  'Origin-Agent-Cluster': '?1',
  'Strict-Transport-Security': 'max-age=31536000',
  'Permissions-Policy': [
    'camera=(self)', 'microphone=(self)', 'geolocation=()', 'payment=()', 'usb=()',
    'display-capture=()', 'browsing-topics=()',
  ].join(', '),
};

const server = http.createServer((req, res) => {
  const url = (req.url || '/').split('?')[0];
  if (req.method === 'GET' && url === '/config') {
    res.writeHead(200, { ...SECURITY_HEADERS, 'Content-Type': 'application/json' });
    return res.end(JSON.stringify(iceConfig()));
  }
  const file = STATIC[url];
  if (req.method !== 'GET' || !file) {
    res.writeHead(404, SECURITY_HEADERS);
    return res.end('Not found');
  }
  res.writeHead(200, { ...SECURITY_HEADERS, 'Content-Type': file.type });
  res.end(file.body);
});

// ---------- signaling relay ----------
//
// One connection can be in many rooms (a client's whole chat list). A room is named by a
// hash of its secret, never the secret itself, and holds at most two connections.
//
// Pairing: a creator asks for a short "slot" number; a joiner who knows it is connected to
// the creator and their SPAKE2 messages are relayed. The slot is only for routing: the rest
// of the code (the password) never reaches the server.

// room id -> Set<WebSocket>
const rooms = new Map();
// slot -> { creator, joiner, timer }
const pairings = new Map();
// ip -> { start, count }
const pairAttempts = new Map();

const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 64 * 1024 });

const send = (ws, msg) => ws.readyState === ws.OPEN && ws.send(JSON.stringify(msg));
const isRoomId = (r) => typeof r === 'string' && /^[0-9a-f]{64}$/.test(r);

function overRateLimit(ws) {
  const now = Date.now();
  if (now - ws.rateStart > RATE_WINDOW_MS) {
    ws.rateStart = now;
    ws.rateCount = 0;
  }
  return ++ws.rateCount > RATE_MAX_MESSAGES;
}

// Behind a proxy (Render, Caddy) the client address is the first X-Forwarded-For entry.
function clientIp(req) {
  const fwd = req.headers['x-forwarded-for'];
  return (typeof fwd === 'string' && fwd.split(',')[0].trim()) || req.socket.remoteAddress || '?';
}

function tooManyPairAttempts(ip) {
  const now = Date.now();
  let a = pairAttempts.get(ip);
  if (!a || now - a.start > PAIR_TTL_MS) pairAttempts.set(ip, (a = { start: now, count: 0 }));
  return ++a.count > PAIR_ATTEMPTS_PER_IP;
}

function freeSlot() {
  // Random rather than sequential, so slots don't reveal how many pairings are running.
  const max = pairings.size < 50 ? 100 : 1000;
  for (let i = 0; i < 1000; i++) {
    const slot = 10 + Math.floor(Math.random() * (max - 10));
    if (!pairings.has(slot)) return slot;
  }
  return null;
}

function endPairing(slot, reason) {
  const p = pairings.get(slot);
  if (!p) return;
  pairings.delete(slot);
  clearTimeout(p.timer);
  for (const ws of [p.creator, p.joiner]) {
    if (!ws) continue;
    if (ws.pairSlot === slot) ws.pairSlot = null;
    if (reason) send(ws, { type: 'pair-ended', reason });
  }
}

function leaveRoom(ws, room) {
  const peers = rooms.get(room);
  if (!peers || !peers.delete(ws)) return;
  ws.rooms.delete(room);
  if (peers.size === 0) rooms.delete(room);
  else for (const p of peers) send(p, { type: 'peer-left', room });
}

function onMessage(ws, m) {
  switch (m.type) {
    case 'join': {
      if (!isRoomId(m.room) || ws.rooms.has(m.room)) return;
      if (ws.rooms.size >= MAX_ROOMS_PER_CLIENT) return send(ws, { type: 'full', room: m.room });
      const peers = rooms.get(m.room) || new Set();
      if (peers.size >= 2) return send(ws, { type: 'full', room: m.room });
      peers.add(ws);
      rooms.set(m.room, peers);
      ws.rooms.add(m.room);
      send(ws, { type: 'joined', room: m.room, peers: peers.size - 1 });
      for (const p of peers) if (p !== ws) send(p, { type: 'peer-joined', room: m.room });
      return;
    }
    case 'leave':
      if (isRoomId(m.room)) leaveRoom(ws, m.room);
      return;
    case 'signal': {
      if (!isRoomId(m.room) || !ws.rooms.has(m.room) || typeof m.data !== 'string') return;
      for (const p of rooms.get(m.room)) if (p !== ws) send(p, { type: 'signal', room: m.room, data: m.data });
      return;
    }
    case 'pair-create': {
      if (ws.pairSlot) endPairing(ws.pairSlot);
      const slot = freeSlot();
      if (slot === null) return send(ws, { type: 'pair-ended', reason: 'busy' });
      const timer = setTimeout(() => endPairing(slot, 'expired'), PAIR_TTL_MS);
      pairings.set(slot, { creator: ws, joiner: null, timer });
      ws.pairSlot = slot;
      return send(ws, { type: 'pair-created', slot, expiresIn: PAIR_TTL_MS });
    }
    case 'pair-join': {
      if (tooManyPairAttempts(ws.ip)) return send(ws, { type: 'pair-ended', reason: 'too-many-attempts' });
      const slot = Number(m.slot);
      const p = pairings.get(slot);
      if (!p || p.joiner || p.creator === ws) return send(ws, { type: 'pair-ended', reason: 'no-such-code' });
      if (ws.pairSlot) endPairing(ws.pairSlot);
      p.joiner = ws;
      ws.pairSlot = slot;
      send(p.creator, { type: 'pair-peer' });
      return send(ws, { type: 'pair-peer' });
    }
    case 'pair-msg': {
      const p = ws.pairSlot && pairings.get(ws.pairSlot);
      if (!p || !p.joiner || typeof m.data !== 'string') return;
      send(p.creator === ws ? p.joiner : p.creator, { type: 'pair-msg', data: m.data });
      return;
    }
    case 'pair-end':
      // Sent by either side when pairing succeeded, failed or was cancelled.
      if (ws.pairSlot) endPairing(ws.pairSlot, typeof m.reason === 'string' ? m.reason.slice(0, 32) : 'cancelled');
      return;
  }
}

wss.on('connection', (ws, req) => {
  // Without an error listener, a protocol error (e.g. an oversized frame) crashes the process.
  ws.on('error', () => ws.terminate());
  if (wss.clients.size > MAX_CLIENTS) return ws.close(1013, 'busy');

  ws.isAlive = true;
  ws.rateStart = Date.now();
  ws.rateCount = 0;
  ws.rooms = new Set();
  ws.pairSlot = null;
  ws.ip = clientIp(req);
  ws.on('pong', () => (ws.isAlive = true));

  ws.on('message', (raw, isBinary) => {
    if (isBinary || overRateLimit(ws)) return ws.terminate();
    let m;
    try {
      m = JSON.parse(raw);
    } catch {
      return ws.close();
    }
    if (!m || typeof m !== 'object') return ws.close();
    onMessage(ws, m);
  });

  ws.on('close', () => {
    for (const room of [...ws.rooms]) leaveRoom(ws, room);
    if (ws.pairSlot) endPairing(ws.pairSlot, 'peer-gone');
  });
});

setInterval(() => {
  const now = Date.now();
  for (const [ip, a] of pairAttempts) if (now - a.start > PAIR_TTL_MS) pairAttempts.delete(ip);
}, 60000).unref();

setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, 30000).unref();

server.listen(PORT, HOST, () => {
  console.log(`Ghostline running on http://${HOST}:${PORT}`);
  if (RELAY_ONLY) console.log('Relay-only mode: IP addresses are hidden from the other person.');
});
