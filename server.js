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
const JOIN_TIMEOUT_MS = 10_000;
const RATE_WINDOW_MS = 10_000;
const RATE_MAX_MESSAGES = 300;

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

// room id (hash of the secret, never the secret itself) -> Set<WebSocket>
const rooms = new Map();
const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 64 * 1024 });

const send = (ws, msg) => ws.readyState === ws.OPEN && ws.send(JSON.stringify(msg));

function overRateLimit(ws) {
  const now = Date.now();
  if (now - ws.rateStart > RATE_WINDOW_MS) {
    ws.rateStart = now;
    ws.rateCount = 0;
  }
  return ++ws.rateCount > RATE_MAX_MESSAGES;
}

wss.on('connection', (ws) => {
  // Without an error listener, a protocol error (e.g. an oversized frame) crashes the process.
  ws.on('error', () => ws.terminate());
  if (wss.clients.size > MAX_CLIENTS) return ws.close(1013, 'busy');

  ws.isAlive = true;
  ws.rateStart = Date.now();
  ws.rateCount = 0;
  ws.joinTimer = setTimeout(() => !ws.room && ws.terminate(), JOIN_TIMEOUT_MS);
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

    if (m.type === 'join' && !ws.room) {
      if (typeof m.room !== 'string' || !/^[0-9a-f]{64}$/.test(m.room)) return ws.close();
      const peers = rooms.get(m.room) || new Set();
      if (peers.size >= 2) {
        send(ws, { type: 'full' });
        return ws.close();
      }
      clearTimeout(ws.joinTimer);
      peers.add(ws);
      rooms.set(m.room, peers);
      ws.room = m.room;
      send(ws, { type: 'joined', peers: peers.size - 1 });
      for (const p of peers) if (p !== ws) send(p, { type: 'peer-joined' });
    } else if (m.type === 'signal' && ws.room && typeof m.data === 'string') {
      for (const p of rooms.get(ws.room)) if (p !== ws) send(p, { type: 'signal', data: m.data });
    }
  });

  ws.on('close', () => {
    clearTimeout(ws.joinTimer);
    const peers = rooms.get(ws.room);
    if (!peers) return;
    peers.delete(ws);
    if (peers.size === 0) rooms.delete(ws.room);
    else for (const p of peers) send(p, { type: 'peer-left' });
  });
});

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
