# Ghostline — full source listing

Generated from the repository on 2026-10-10. Every file below is reproduced in full, verbatim, straight from the tracked sources.

Not listed (generated, binary, or a lockfile, so not hand-written source): `android/gradle/wrapper/gradle-wrapper.jar`, `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/gradle-wrapper.properties`, `package-lock.json`, and the Markdown docs (`README.md`, `android/README.md`, this file). Run `./gradlew wrapper` to regenerate the Gradle wrapper, and `npm install` to regenerate the lockfile.

## Files

- [`server.js`](#serverjs) — 305 lines
- [`package.json`](#packagejson) — 17 lines
- [`render.yaml`](#renderyaml) — 11 lines
- [`.gitignore`](#gitignore) — 4 lines
- [`public/index.html`](#publicindexhtml) — 144 lines
- [`public/style.css`](#publicstylecss) — 190 lines
- [`public/app.js`](#publicappjs) — 1294 lines
- [`test/server.test.js`](#testservertestjs) — 130 lines
- [`android/.gitignore`](#androidgitignore) — 9 lines
- [`android/app/build.gradle.kts`](#androidappbuildgradlekts) — 59 lines
- [`android/app/src/main/AndroidManifest.xml`](#androidappsrcmainandroidmanifestxml) — 39 lines
- [`android/app/src/main/java/app/plainnotes/CallAudio.kt`](#androidappsrcmainjavaappplainnotescallaudiokt) — 40 lines
- [`android/app/src/main/java/app/plainnotes/Crypto.kt`](#androidappsrcmainjavaappplainnotescryptokt) — 146 lines
- [`android/app/src/main/java/app/plainnotes/Hub.kt`](#androidappsrcmainjavaappplainnoteshubkt) — 1111 lines
- [`android/app/src/main/java/app/plainnotes/Lock.kt`](#androidappsrcmainjavaappplainnoteslockkt) — 121 lines
- [`android/app/src/main/java/app/plainnotes/MainActivity.kt`](#androidappsrcmainjavaappplainnotesmainactivitykt) — 1439 lines
- [`android/app/src/main/java/app/plainnotes/Store.kt`](#androidappsrcmainjavaappplainnotesstorekt) — 85 lines
- [`android/app/src/main/res/drawable/ic_add.xml`](#androidappsrcmainresdrawableic_addxml) — 3 lines
- [`android/app/src/main/res/drawable/ic_back.xml`](#androidappsrcmainresdrawableic_backxml) — 3 lines
- [`android/app/src/main/res/drawable/ic_call.xml`](#androidappsrcmainresdrawableic_callxml) — 3 lines
- [`android/app/src/main/res/drawable/ic_call_end.xml`](#androidappsrcmainresdrawableic_call_endxml) — 3 lines
- [`android/app/src/main/res/drawable/ic_chat.xml`](#androidappsrcmainresdrawableic_chatxml) — 3 lines
- [`android/app/src/main/res/drawable/ic_flip.xml`](#androidappsrcmainresdrawableic_flipxml) — 3 lines
- [`android/app/src/main/res/drawable/ic_keypad.xml`](#androidappsrcmainresdrawableic_keypadxml) — 3 lines
- [`android/app/src/main/res/drawable/ic_launcher_background.xml`](#androidappsrcmainresdrawableic_launcher_backgroundxml) — 9 lines
- [`android/app/src/main/res/drawable/ic_launcher_foreground.xml`](#androidappsrcmainresdrawableic_launcher_foregroundxml) — 13 lines
- [`android/app/src/main/res/drawable/ic_mic.xml`](#androidappsrcmainresdrawableic_micxml) — 3 lines
- [`android/app/src/main/res/drawable/ic_more.xml`](#androidappsrcmainresdrawableic_morexml) — 3 lines
- [`android/app/src/main/res/drawable/ic_send.xml`](#androidappsrcmainresdrawableic_sendxml) — 3 lines
- [`android/app/src/main/res/drawable/ic_shield.xml`](#androidappsrcmainresdrawableic_shieldxml) — 3 lines
- [`android/app/src/main/res/drawable/ic_speaker.xml`](#androidappsrcmainresdrawableic_speakerxml) — 3 lines
- [`android/app/src/main/res/drawable/ic_timer.xml`](#androidappsrcmainresdrawableic_timerxml) — 3 lines
- [`android/app/src/main/res/drawable/ic_video.xml`](#androidappsrcmainresdrawableic_videoxml) — 3 lines
- [`android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`](#androidappsrcmainresmipmap-anydpi-v26ic_launcherxml) — 6 lines
- [`android/app/src/main/res/xml/network_security_config.xml`](#androidappsrcmainresxmlnetwork_security_configxml) — 9 lines
- [`android/app/src/test/java/app/plainnotes/CryptoTest.kt`](#androidappsrctestjavaappplainnotescryptotestkt) — 72 lines
- [`android/app/src/test/java/app/plainnotes/LockTest.kt`](#androidappsrctestjavaappplainnoteslocktestkt) — 28 lines
- [`android/build.gradle.kts`](#androidbuildgradlekts) — 4 lines
- [`android/gradle.properties`](#androidgradleproperties) — 4 lines
- [`android/settings.gradle.kts`](#androidsettingsgradlekts) — 16 lines

**40 files, 5347 lines.**


## `server.js`

<a id="serverjs"></a>

```javascript
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
// Pairing: a creator asks for a 6-letter code; the first person to enter it is connected to
// the creator (auto-admit) and their key-exchange messages are relayed. Codes are single use,
// expire, and joins are rate-limited per address.

// room id -> Set<WebSocket>
const rooms = new Map();
// code -> { creator, joiner, timer }
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

// 24 letters without I and O, so codes are easy to read aloud and type: 24^6 ≈ 191 million.
const CODE_LETTERS = 'ABCDEFGHJKLMNPQRSTUVWXYZ';
const CODE_LENGTH = 6;

function newCode() {
  for (let i = 0; i < 100; i++) {
    const bytes = crypto.randomBytes(CODE_LENGTH * 2);
    let code = '';
    for (let j = 0; code.length < CODE_LENGTH && j < bytes.length; j++) {
      if (bytes[j] < 240) code += CODE_LETTERS[bytes[j] % 24]; // 240 = 10 * 24, no bias
    }
    if (code.length === CODE_LENGTH && !pairings.has(code)) return code;
  }
  return null;
}

const normalizeCode = (c) => (typeof c === 'string' ? c.toUpperCase().replace(/[^A-Z]/g, '') : '');

function endPairing(code, reason) {
  const p = pairings.get(code);
  if (!p) return;
  pairings.delete(code);
  clearTimeout(p.timer);
  for (const ws of [p.creator, p.joiner]) {
    if (!ws) continue;
    if (ws.pairCode === code) ws.pairCode = null;
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
      if (ws.pairCode) endPairing(ws.pairCode);
      const code = newCode();
      if (code === null) return send(ws, { type: 'pair-ended', reason: 'busy' });
      const timer = setTimeout(() => endPairing(code, 'expired'), PAIR_TTL_MS);
      pairings.set(code, { creator: ws, joiner: null, timer });
      ws.pairCode = code;
      return send(ws, { type: 'pair-created', code, expiresIn: PAIR_TTL_MS });
    }
    case 'pair-join': {
      if (tooManyPairAttempts(ws.ip)) return send(ws, { type: 'pair-ended', reason: 'too-many-attempts' });
      const code = normalizeCode(m.code);
      const p = pairings.get(code);
      if (!p || p.joiner || p.creator === ws) return send(ws, { type: 'pair-ended', reason: 'no-such-code' });
      if (ws.pairCode) endPairing(ws.pairCode);
      p.joiner = ws;
      ws.pairCode = code;
      send(p.creator, { type: 'pair-peer' });
      return send(ws, { type: 'pair-peer' });
    }
    case 'pair-msg': {
      const p = ws.pairCode && pairings.get(ws.pairCode);
      if (!p || !p.joiner || typeof m.data !== 'string') return;
      send(p.creator === ws ? p.joiner : p.creator, { type: 'pair-msg', data: m.data });
      return;
    }
    case 'pair-end':
      // Sent by either side when pairing succeeded, failed or was cancelled.
      if (ws.pairCode) endPairing(ws.pairCode, typeof m.reason === 'string' ? m.reason.slice(0, 32) : 'cancelled');
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
  ws.pairCode = null;
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
    if (ws.pairCode) endPairing(ws.pairCode, 'peer-gone');
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
```


## `package.json`

<a id="packagejson"></a>

```json
{
  "name": "ghostline",
  "version": "1.0.0",
  "private": true,
  "description": "Private, ephemeral, end-to-end encrypted chat, voice and video between two people.",
  "main": "server.js",
  "scripts": {
    "start": "node server.js",
    "test": "node --test test/*.test.js"
  },
  "dependencies": {
    "ws": "^8.18.0"
  },
  "engines": {
    "node": ">=20"
  }
}
```


## `render.yaml`

<a id="renderyaml"></a>

```yaml
services:
  - type: web
    name: ghostline
    runtime: node
    plan: free
    buildCommand: npm ci
    startCommand: npm start
    healthCheckPath: /
    envVars:
      - key: HOST
        value: 0.0.0.0
```


## `.gitignore`

<a id="gitignore"></a>

```gitignore
node_modules/
.DS_Store
.env
*.log
```


## `public/index.html`

<a id="publicindexhtml"></a>

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
  <meta name="referrer" content="no-referrer">
  <meta name="robots" content="noindex, nofollow">
  <meta name="color-scheme" content="dark">
  <title>Ghostline</title>
  <link rel="icon" href="data:,">
  <link rel="stylesheet" href="/style.css">
  <script src="/app.js" defer></script>
</head>
<body>
  <!-- chat list -->
  <main id="home" class="page" hidden>
    <header class="top">
      <h1>Ghostline</h1>
      <span id="netStatus" class="net"></span>
    </header>
    <div class="row pad">
      <button id="newRoomBtn" class="primary grow">New room</button>
      <button id="joinRoomBtn" class="grow">Join with code</button>
    </div>
    <ul id="roomList" class="rooms"></ul>
    <p id="emptyRooms" class="empty pad" hidden>No rooms yet. Tap <strong>New room</strong> to get a code,
      or <strong>Join with code</strong> if someone gave you one.</p>
    <p id="memoryOnly" class="warn pad" hidden>This browser isn't saving data (private mode?), so rooms
      will be forgotten when you close this tab.</p>
    <p class="warn pad">No website can protect you from spyware already on your device that records
      the screen, keyboard or microphone. Use a device you trust.</p>
  </main>

  <!-- pairing: show a code, or type one -->
  <section id="pair" class="page" hidden>
    <header class="top">
      <button id="pairBack" class="icon" aria-label="Back">←</button>
      <h2 id="pairTitle">New room</h2>
    </header>
    <div class="pad">
      <div id="pairShow" hidden>
        <p class="muted">Give this code to <strong>one</strong> person, in person or over a channel you trust.
          It works once and expires in 10 minutes.</p>
        <div id="pairCode" class="code">— — —</div>
        <p id="pairExpiry" class="muted center"></p>
      </div>
      <form id="pairEnter" hidden>
        <p class="muted">Enter the code the other person sees on their screen.</p>
        <input id="codeInput" class="code-input" autocomplete="off" autocorrect="off"
               autocapitalize="characters" spellcheck="false" placeholder="KQD-MWP" maxlength="7">
        <button id="codeJoinBtn" class="primary big full">Join</button>
      </form>
      <form id="pairName" hidden>
        <p class="ok-text">✓ Paired securely.</p>
        <label class="muted" for="roomNameInput">Name this room (only you see this name)</label>
        <input id="roomNameInput" maxlength="40" autocomplete="off" spellcheck="false">
        <button class="primary big full">Save</button>
      </form>
      <p id="pairStatus" class="status-line"></p>
    </div>
  </section>

  <!-- one room -->
  <section id="room" class="room page" hidden>
    <header class="bar">
      <button id="roomBack" class="icon" aria-label="Back to rooms">←</button>
      <div class="title">
        <strong id="roomTitle"></strong>
        <div class="status"><span id="dot" class="dot"></span><span id="statusText"></span></div>
      </div>
      <div class="actions">
        <button id="voiceBtn" disabled>Voice</button>
        <button id="videoBtn" disabled>Video</button>
        <button id="menuBtn" class="icon" aria-label="Room options">⋯</button>
      </div>
    </header>

    <div id="menu" class="panel menu" hidden>
      <span id="roleTag" class="role-tag"></span>
      <button id="renameBtn">Rename</button>
      <button id="clearBtn">Clear chat</button>
      <button id="removeBtn" class="danger" hidden>Remove participant</button>
      <button id="leaveBtn" class="danger">Leave room</button>
    </div>

    <div id="safetyBox" class="safety">
      Safety code: <strong id="safety">—</strong>
      <button id="verifyBtn" class="verify" type="button" hidden>Mark verified</button>
      <span class="verified-tag">✓ verified</span>
      <span class="hint">Compare it out loud on a call. If both screens match, nobody is in the middle.
        Once verified, you'll be warned if it ever changes.</span>
    </div>

    <div id="call" class="call" hidden>
      <video id="remoteVideo" autoplay playsinline></video>
      <div id="avatar" class="avatar"><span id="callLabel">Calling…</span></div>
      <video id="localVideo" autoplay playsinline muted></video>
      <div class="controls">
        <button id="micBtn">Mute</button>
        <button id="camBtn">Camera on</button>
        <button id="flipBtn" hidden>Flip</button>
        <button id="hangupBtn" class="danger">Hang up</button>
      </div>
    </div>

    <div id="messages" class="messages" aria-live="polite"></div>

    <form id="composer" class="composer">
      <select id="ttl" title="Disappearing messages">
        <option value="0">Keep</option>
        <option value="30">30s</option>
        <option value="300">5m</option>
        <option value="3600">1h</option>
      </select>
      <input id="msgInput" placeholder="Message" maxlength="4000"
             autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false">
      <button id="sendBtn" class="primary">Send</button>
    </form>
  </section>

  <!-- shown over any page -->
  <div id="incoming" class="incoming" hidden>
    <span id="incomingText">Incoming call</span>
    <div class="row">
      <button id="acceptVoiceBtn" class="primary">Answer voice</button>
      <button id="acceptVideoBtn" class="primary">Answer video</button>
      <button id="declineBtn" class="danger">Decline</button>
    </div>
  </div>
  <button id="callBar" class="callbar" hidden>Call in progress · tap to return</button>

  <dialog id="dialog">
    <div class="dialog-body">
      <h3 id="dialogTitle"></h3>
      <p id="dialogText"></p>
      <input id="dialogInput" maxlength="40" autocomplete="off" spellcheck="false" hidden>
      <div class="row end">
        <button id="dialogCancel" type="button">Cancel</button>
        <button id="dialogOk" type="button" class="primary">OK</button>
      </div>
    </div>
  </dialog>
</body>
</html>
```


## `public/style.css`

<a id="publicstylecss"></a>

```css
:root {
  --bg: #0d0f12;
  --surface: #161a20;
  --surface-2: #1f252d;
  --border: #2a313b;
  --text: #e7eaee;
  --muted: #8b95a3;
  --accent: #4f8cff;
  --ok: #3ccf8e;
  --wait: #f2b84b;
  --danger: #ef5b5b;
  --me: #2b4a86;
  --them: #262c35;
}

* { box-sizing: border-box; }
[hidden] { display: none !important; }

html, body { height: 100%; margin: 0; }
body {
  background: var(--bg);
  color: var(--text);
  font: 15px/1.45 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  -webkit-text-size-adjust: 100%;
}
/* Hide everything when the tab is in the background (app switcher thumbnails). */
body.veil > * { visibility: hidden; }

button, input, select {
  font: inherit;
  color: inherit;
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 8px 12px;
}
button { cursor: pointer; }
button:disabled { opacity: .45; cursor: default; }
button.primary { background: var(--accent); border-color: var(--accent); color: #fff; }
button.danger { background: transparent; border-color: var(--danger); color: var(--danger); }
button.danger-fill { background: var(--danger); border-color: var(--danger); color: #fff; }
button.big { padding: 14px 22px; font-size: 17px; }
button.full { width: 100%; }
button.icon { background: transparent; border-color: transparent; font-size: 20px; padding: 4px 10px; }
input:focus, select:focus, button:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }

.page {
  max-width: 640px; margin: 0 auto;
  padding: env(safe-area-inset-top) 0 env(safe-area-inset-bottom);
}
.pad { padding: 0 16px; margin: 12px 0; }
.row { display: flex; gap: 8px; flex-wrap: wrap; }
.row.end { justify-content: flex-end; }
.grow { flex: 1; }
.muted { color: var(--muted); }
.center { text-align: center; }
.ok-text { color: var(--ok); font-weight: 600; }
.warn {
  background: #2a2211; border: 1px solid #5b4718; color: #f3d38b;
  border-radius: 10px; padding: 12px 14px; font-size: 14px;
}
.top { display: flex; align-items: center; gap: 8px; padding: 14px 16px 4px; }
.top h1 { font-size: 26px; margin: 0; letter-spacing: -.5px; flex: 1; }
.top h2 { font-size: 20px; margin: 0; }
.net { color: var(--wait); font-size: 13px; }
.empty { color: var(--muted); }

/* Room list */
.rooms { list-style: none; margin: 8px 0; padding: 0 8px; }
.room-item {
  display: flex; align-items: center; gap: 12px; width: 100%; text-align: left;
  background: transparent; border: 0; border-radius: 12px; padding: 12px 10px;
}
.room-item:hover { background: var(--surface); }
.room-body { display: flex; flex-direction: column; min-width: 0; flex: 1; }
.preview { color: var(--muted); font-size: 14px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.badge {
  background: var(--accent); color: #fff; border-radius: 999px; font-size: 12px;
  min-width: 22px; padding: 2px 7px; text-align: center; font-weight: 600;
}

/* Pairing */
.code {
  font: 600 40px/1.2 ui-monospace, Menlo, monospace; letter-spacing: 2px; text-align: center;
  background: var(--surface); border: 1px solid var(--border); border-radius: 14px; padding: 22px 8px; margin: 18px 0 8px;
}
.code-input { width: 100%; font: 600 28px ui-monospace, Menlo, monospace; text-align: center; letter-spacing: 2px; margin: 8px 0 12px; }
#pairName input { width: 100%; margin: 6px 0 12px; }
.status-line { color: var(--muted); min-height: 1.5em; }
.status-line.bad { color: var(--danger); }

/* Room */
.room { display: flex; flex-direction: column; height: 100%; max-width: 900px; }
.bar {
  display: flex; gap: 6px; align-items: center;
  padding: 8px 10px; border-bottom: 1px solid var(--border); background: var(--surface);
}
.bar .title { flex: 1; min-width: 0; }
.bar .title strong { display: block; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.status { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--muted); min-width: 0; }
.status span:last-child { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.dot { width: 10px; height: 10px; border-radius: 50%; background: var(--wait); flex: none; }
.dot.ok { background: var(--ok); }
.dot.bad { background: var(--danger); }
.dot.off { background: #4a525e; }
.actions { display: flex; gap: 6px; flex: none; }
.actions button { padding: 6px 10px; font-size: 14px; }
.actions button.icon { padding: 4px 8px; }

.panel { margin: 8px 12px 0; padding: 10px; background: var(--surface); border: 1px solid var(--border); border-radius: 12px; }
.menu { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; align-items: center; }
.role-tag { margin-right: auto; font-size: 12px; color: var(--muted); }

.safety { padding: 8px 16px; font-size: 13px; color: var(--muted); }
.safety strong { color: var(--text); font-family: ui-monospace, Menlo, monospace; letter-spacing: 1px; }
.safety .hint { display: block; font-size: 12px; }
.safety .verify { padding: 2px 8px; font-size: 12px; margin-left: 6px; }
.safety .verified-tag { display: none; color: var(--ok); margin-left: 6px; }
.safety.verified .verified-tag { display: inline; }
.safety.verified .hint { display: none; }
.safety.changed strong { color: var(--danger); }

/* Call */
.call {
  position: relative; margin: 0 12px; border-radius: 14px; overflow: hidden;
  background: #000; height: min(52vh, 480px); flex: none;
}
#remoteVideo { width: 100%; height: 100%; object-fit: cover; }
.avatar {
  position: absolute; inset: 0; display: grid; place-items: center;
  color: var(--muted); font-size: 18px; background: radial-gradient(circle, #1d2633, #000);
}
#localVideo {
  position: absolute; right: 10px; top: 10px; width: 28%; max-width: 160px;
  border-radius: 10px; border: 1px solid var(--border); background: #111; transform: scaleX(-1);
}
#localVideo.back { transform: none; }
.controls {
  position: absolute; left: 0; right: 0; bottom: 0; display: flex; gap: 8px; justify-content: center;
  padding: 12px; background: linear-gradient(transparent, rgba(0,0,0,.7));
}
.controls button { background: rgba(31,37,45,.85); }
.controls button.danger { background: var(--danger); color: #fff; }

/* Banners over any page */
.incoming {
  position: fixed; left: 12px; right: 12px; top: calc(12px + env(safe-area-inset-top)); z-index: 10;
  max-width: 616px; margin: 0 auto; display: flex; flex-direction: column; gap: 10px;
  background: var(--surface); border: 1px solid var(--accent); border-radius: 14px; padding: 14px;
  box-shadow: 0 10px 30px rgba(0,0,0,.5);
}
.callbar {
  position: fixed; left: 0; right: 0; bottom: 0; z-index: 9; border-radius: 0;
  background: var(--ok); border-color: var(--ok); color: #04210f; font-weight: 600;
  padding: 10px calc(10px + env(safe-area-inset-bottom));
}

/* Chat */
.messages { flex: 1; overflow-y: auto; padding: 12px 16px; display: flex; flex-direction: column; gap: 6px; }
.msg {
  max-width: 80%; padding: 8px 12px; border-radius: 14px; white-space: pre-wrap; overflow-wrap: anywhere;
}
.msg.me { align-self: flex-end; background: var(--me); border-bottom-right-radius: 4px; }
.msg.them { align-self: flex-start; background: var(--them); border-bottom-left-radius: 4px; }
.msg time { display: block; font-size: 11px; color: var(--muted); margin-top: 2px; }
.sys { align-self: center; color: var(--muted); font-size: 12px; text-align: center; max-width: 90%; }

.composer {
  display: flex; gap: 6px; padding: 10px 16px; border-top: 1px solid var(--border); background: var(--surface);
}
.composer input { flex: 1; min-width: 0; }
.composer select { padding: 8px 6px; }

/* Dialog */
dialog {
  background: var(--surface); color: var(--text); border: 1px solid var(--border); border-radius: 14px;
  padding: 18px; width: min(420px, calc(100vw - 32px));
}
dialog::backdrop { background: rgba(0,0,0,.6); }
dialog h3 { margin: 0 0 8px; }
dialog p { color: var(--muted); margin: 0 0 14px; }
dialog input { width: 100%; margin-bottom: 14px; }

@media (max-width: 520px) {
  .composer { gap: 4px; padding: 8px 10px; }
  .composer button, .composer select { padding: 8px; }
  .call { height: 46vh; }
  .code { font-size: 32px; }
  .actions button { padding: 6px 8px; }
}
```


## `public/app.js`

<a id="publicappjs"></a>

```javascript
'use strict';

// How it works:
// - Two devices pair once with a 6-letter code: the server connects whoever enters it first,
//   and the devices run an ECDH key exchange to get a strong room secret that never crosses
//   the network. Compare the safety code once to rule out a server in the middle.
//   The room is then saved on each device.
// - From the secret we derive (HKDF) a room id, the only thing the server sees, and an AES-GCM
//   key that encrypts all signaling, so the server can't read or forge the WebRTC handshake.
// - Chat runs over a WebRTC data channel and calls over SRTP, directly between the devices.
// - Rooms and chat history are stored in IndexedDB, encrypted with a non-extractable AES key.
//   Messages typed while the other person is offline wait here and are sent when both are online.

const PAD_BLOCK = 256; // data channel messages are padded to a multiple of this many bytes

const $ = (id) => document.getElementById(id);
const te = new TextEncoder();
const td = new TextDecoder();

const b64u = {
  to: (buf) =>
    btoa(String.fromCharCode(...new Uint8Array(buf instanceof ArrayBuffer ? buf : buf.buffer)))
      .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''),
  from: (s) => {
    s = s.replace(/-/g, '+').replace(/_/g, '/');
    while (s.length % 4) s += '=';
    return Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
  },
};
const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
const newId = () => hex(crypto.getRandomValues(new Uint8Array(8)).buffer);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------- room keys ----------

async function roomKeys(secret) {
  const raw = b64u.from(secret);
  if (raw.length < 16) throw new Error('bad secret');
  const base = await crypto.subtle.importKey('raw', raw, 'HKDF', false, ['deriveBits', 'deriveKey']);
  const salt = te.encode('ghostline-v1');
  const roomBits = await crypto.subtle.deriveBits(
    { name: 'HKDF', hash: 'SHA-256', salt, info: te.encode('room-id') }, base, 256);
  const id = hex(roomBits);
  const key = await crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt, info: te.encode('signal-key') },
    base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
  return { id, key };
}

async function seal(keys, obj) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: te.encode(keys.id) }, keys.key, te.encode(JSON.stringify(obj)));
  return b64u.to(iv) + '.' + b64u.to(ct);
}

async function unseal(keys, str) {
  try {
    const [iv, ct] = str.split('.');
    const pt = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: b64u.from(iv), additionalData: te.encode(keys.id) }, keys.key, b64u.from(ct));
    return JSON.parse(td.decode(pt));
  } catch {
    return null; // tampered or not from our peer: ignore
  }
}

// ---------- storage: IndexedDB, encrypted with a key that can't be exported ----------

const store = {
  db: null,
  key: null,
  mem: new Map(), // fallback when the browser won't persist (some private modes)
  writes: Promise.resolve(),

  async open() {
    try {
      this.db = await new Promise((resolve, reject) => {
        const req = indexedDB.open('ghostline', 1);
        req.onupgradeneeded = () => req.result.createObjectStore('kv');
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
      });
      this.key = await this.raw('get', 'master');
      if (!this.key) {
        this.key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
        await this.raw('put', 'master', this.key);
      }
    } catch {
      this.db = null;
      this.key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
    }
  },

  raw(op, k, v) {
    if (!this.db) {
      if (op === 'get') return Promise.resolve(this.mem.get(k));
      if (op === 'put') this.mem.set(k, v); else this.mem.delete(k);
      return Promise.resolve();
    }
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction('kv', op === 'get' ? 'readonly' : 'readwrite');
      const s = tx.objectStore('kv');
      const req = op === 'get' ? s.get(k) : op === 'put' ? s.put(v, k) : s.delete(k);
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  },

  async get(k) {
    const v = await this.raw('get', k);
    if (!v) return null;
    try {
      return JSON.parse(td.decode(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: v.iv }, this.key, v.ct)));
    } catch {
      return null;
    }
  },

  // Writes are queued so two saves of the same key can't interleave.
  put(k, obj) {
    this.writes = this.writes.then(async () => {
      const iv = crypto.getRandomValues(new Uint8Array(12));
      const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, this.key, te.encode(JSON.stringify(obj)));
      await this.raw('put', k, { iv, ct });
    }).catch((e) => console.error('save failed', e));
    return this.writes;
  },

  del(k) {
    this.writes = this.writes.then(() => this.raw('delete', k)).catch(() => {});
    return this.writes;
  },
};

// ---------- app state ----------

const rooms = new Map(); // room id -> Room
let current = null; // Room on screen
let pendingNotice = null; // a one-off message to show when we next land on the home screen
let saveRoomsTimer = null;

function saveRooms() {
  clearTimeout(saveRoomsTimer);
  saveRoomsTimer = setTimeout(() => store.put('rooms', [...rooms.values()].map((r) => r.rec)), 50);
}

// ---------- server connection (one WebSocket for every room) ----------

const net = {
  ws: null,
  backoff: 1000,
  queue: Promise.resolve(),

  connect() {
    const ws = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`);
    this.ws = ws;
    ws.onopen = () => {
      this.backoff = 1000;
      for (const r of rooms.values()) r.join();
      renderAll();
    };
    ws.onmessage = (e) => {
      this.queue = this.queue.then(() => onServerMessage(JSON.parse(e.data))).catch((err) => console.error(err));
    };
    ws.onclose = () => {
      if (this.ws !== ws) return;
      this.ws = null;
      for (const r of rooms.values()) r.serverPeer = false;
      pairing.onDisconnect();
      renderAll();
      setTimeout(() => this.connect(), this.backoff);
      this.backoff = Math.min(this.backoff * 2, 30000);
    };
  },

  open() {
    return this.ws?.readyState === WebSocket.OPEN;
  },

  send(msg) {
    if (!this.open()) return false;
    this.ws.send(JSON.stringify(msg));
    return true;
  },
};

async function onServerMessage(m) {
  if (m.room) return rooms.get(m.room)?.onServer(m);
  return pairing.onServer(m);
}

// ---------- a room: one peer connection, chat, saved history ----------

class Room {
  constructor(rec, keys, msgs, cert) {
    this.rec = rec; // saved: { id, secret, name, created, verifiedCode, unread, ended }
    this.id = rec.id;
    this.keys = keys;
    this.msgs = msgs; // saved: [{ id, mine, sys, text, ts, ttl, expires, status }]
    this.cert = cert; // kept per room so the safety code stays the same across reconnects
    this.pc = null;
    this.dc = null;
    this.polite = false;
    this.makingOffer = false;
    this.ignoreOffer = false;
    this.serverPeer = false; // the other person is connected to the server
    this.full = false;
    this.code = null;
    this.remote = new MediaStream();
    // replay protection for signaling: fresh id per peer session + increasing counter
    this.me = newId();
    this.seq = 0;
    this.peerId = null;
    this.peerSeq = 0;
    this.expiryTimers = new Map();
    for (const m of this.msgs) this.scheduleExpiry(m);
  }

  static async load(rec) {
    const keys = await roomKeys(rec.secret);
    const msgs = (await store.get('msgs:' + rec.id)) || [];
    const cert = await store.raw('get', 'cert:' + rec.id).catch(() => null);
    return new Room(rec, keys, msgs.filter((m) => !m.expires || m.expires > Date.now()), cert || null);
  }

  saveMsgs() {
    store.put('msgs:' + this.id, this.msgs);
  }

  join() {
    if (!this.rec.ended) net.send({ type: 'join', room: this.id });
  }

  dcOpen() {
    return this.dc?.readyState === 'open';
  }

  // --- server events ---

  resetSession() {
    this.me = newId();
    this.seq = 0;
    this.peerId = null;
    this.peerSeq = 0;
  }

  async onServer(m) {
    switch (m.type) {
      case 'joined':
        this.full = false;
        this.resetSession();
        this.serverPeer = m.peers > 0;
        if (this.serverPeer) {
          this.teardown();
          this.polite = true;
          this.createPeer();
        }
        break;
      case 'peer-joined':
        this.resetSession();
        this.teardown();
        this.serverPeer = true;
        this.polite = false;
        this.createPeer();
        this.setupDc(this.pc.createDataChannel('chat', { ordered: true }));
        break;
      case 'peer-left':
        this.serverPeer = false;
        // Their server connection dropped; the direct connection may still be fine.
        if (!this.dcOpen()) this.teardown();
        break;
      case 'full':
        this.full = true; // usually: this room is already open in another tab or device
        break;
      case 'signal': {
        const data = await unseal(this.keys, m.data);
        if (!data || data.from === this.me) return;
        if (this.peerId && data.from !== this.peerId) return;
        if (!(Number.isSafeInteger(data.seq) && data.seq > this.peerSeq)) return;
        this.peerId = data.from;
        this.peerSeq = data.seq;
        await this.onSignal(data);
        break;
      }
    }
    renderAll();
  }

  async sendSignal(obj) {
    const data = await seal(this.keys, { ...obj, from: this.me, seq: ++this.seq });
    net.send({ type: 'signal', room: this.id, data });
  }

  // --- WebRTC (perfect negotiation) ---

  createPeer() {
    const pc = new RTCPeerConnection({
      iceServers: ice.iceServers,
      iceTransportPolicy: ice.relayOnly ? 'relay' : 'all',
      certificates: this.cert ? [this.cert] : undefined,
    });
    this.pc = pc;
    pc.onicecandidate = ({ candidate }) => candidate && this.pc === pc && this.sendSignal({ candidate });
    pc.onnegotiationneeded = async () => {
      try {
        this.makingOffer = true;
        await pc.setLocalDescription();
        await this.sendSignal({ description: pc.localDescription });
      } catch (err) {
        console.error(err);
      } finally {
        this.makingOffer = false;
      }
    };
    pc.ontrack = ({ track }) => {
      if (!this.remote.getTracks().includes(track)) this.remote.addTrack(track);
      if (call.room === this) attachRemote();
    };
    pc.ondatachannel = (e) => this.setupDc(e.channel);
    pc.onsignalingstatechange = () => pc.signalingState === 'stable' && this.updateSafetyCode();
    pc.onconnectionstatechange = () => {
      if (this.pc !== pc) return;
      if (pc.connectionState === 'failed') pc.restartIce();
      renderAll();
    };
  }

  async onSignal({ description, candidate }) {
    if (!this.pc) this.createPeer();
    const pc = this.pc;
    if (description) {
      const collision = description.type === 'offer' && (this.makingOffer || pc.signalingState !== 'stable');
      this.ignoreOffer = !this.polite && collision;
      if (this.ignoreOffer) return;
      await pc.setRemoteDescription(description);
      if (description.type === 'offer') {
        await pc.setLocalDescription();
        await this.sendSignal({ description: pc.localDescription });
      }
    } else if (candidate) {
      try {
        await pc.addIceCandidate(candidate);
      } catch (err) {
        if (!this.ignoreOffer) console.error(err);
      }
    }
  }

  teardown() {
    if (call.room === this) endCall(false);
    this.dc?.close();
    this.pc?.close();
    this.dc = null;
    this.pc = null;
    this.remote.getTracks().forEach((t) => this.remote.removeTrack(t));
    this.code = null;
  }

  async updateSafetyCode() {
    const fp = (sdp) => sdp?.match(/a=fingerprint:\S+ (\S+)/)?.[1]?.toUpperCase();
    const a = fp(this.pc?.localDescription?.sdp);
    const b = fp(this.pc?.remoteDescription?.sdp);
    if (!a || !b) return;
    const h = new Uint8Array(await crypto.subtle.digest('SHA-256', te.encode([a, b].sort().join('|'))));
    const code = [0, 1, 2, 3]
      .map((i) => String(((h[i * 2] << 8) | h[i * 2 + 1]) % 10000).padStart(4, '0'))
      .join(' ');
    if (code === this.code) return;
    this.code = code;
    // Each device keeps its own key for this room, so the code only changes if the device
    // on the other end changes (or someone is in the middle).
    if (this.rec.verifiedCode && code !== this.rec.verifiedCode) {
      this.addSys('⚠ The safety code changed. This may be a different device or someone in the middle. ' +
        'Verify it again before saying anything sensitive.');
    }
    renderAll();
  }

  // --- data channel: chat + call control ---

  setupDc(ch) {
    this.dc = ch;
    ch.onopen = () => {
      this.flushOutbox();
      renderAll();
    };
    ch.onclose = () => {
      if (this.dc !== ch) return;
      if (call.room === this) endCall(false);
      renderAll();
    };
    ch.onmessage = (e) => {
      try {
        this.onPeerMessage(JSON.parse(e.data));
      } catch (err) {
        console.error(err);
      }
    };
  }

  // Pad every message to a multiple of PAD_BLOCK bytes, so its encrypted size on the network
  // doesn't reveal how long it is.
  sendPeer(obj) {
    if (!this.dcOpen()) return false;
    const len = te.encode(JSON.stringify(obj)).length + 7; // + ',"p":""'
    const pad = Math.ceil(len / PAD_BLOCK) * PAD_BLOCK - len;
    this.dc.send(JSON.stringify({ ...obj, p: '.'.repeat(pad) }));
    return true;
  }

  flushOutbox() {
    let changed = false;
    for (const m of this.msgs) {
      // Resend anything not yet acknowledged: a message "sent" into a connection that had
      // silently died is never delivered otherwise. The receiver ignores duplicates by id.
      if (m.mine && m.status !== 'delivered' && this.sendPeer({ t: 'msg', id: m.id, text: m.text, ttl: m.ttl, ts: m.ts })) {
        if (m.status === 'pending') {
          m.status = 'sent';
          changed = true;
        }
      }
    }
    if (changed) this.saveMsgs();
  }

  onPeerMessage(m) {
    switch (m.t) {
      case 'msg': {
        const id = String(m.id || newId()).slice(0, 32);
        this.sendPeer({ t: 'ack', id });
        if (this.msgs.some((x) => x.id === id && !x.mine)) return; // already have it
        const ttl = Math.max(0, Math.min(86400, Number(m.ttl) || 0));
        const msg = {
          id, mine: false, text: String(m.text).slice(0, 4000), ts: Date.now(),
          ttl, expires: ttl ? Date.now() + ttl * 1000 : 0,
        };
        this.msgs.push(msg);
        this.scheduleExpiry(msg);
        if (current !== this || document.hidden) this.rec.unread = (this.rec.unread || 0) + 1;
        this.saveMsgs();
        saveRooms();
        break;
      }
      case 'ack': {
        const msg = this.msgs.find((x) => x.mine && x.id === m.id);
        if (msg && msg.status !== 'delivered') {
          msg.status = 'delivered';
          this.saveMsgs();
        }
        break;
      }
      case 'clear':
        this.msgs = [];
        this.addSys('The other person cleared the chat.');
        break;
      case 'bye': {
        const who = this.rec.role === 'owner' ? 'The participant' : 'The other person';
        this.rec.ended = true;
        saveRooms();
        net.send({ type: 'leave', room: this.id });
        this.addSys(who + ' left this room. You can delete it from the ⋯ menu.');
        this.teardown();
        break;
      }
      case 'end-room':
        // Only the owner may end for both; a participant honours it.
        if (this.rec.role === 'owner') break;
        pendingNotice = 'The owner ended this room, so it was deleted here too.';
        wipeRoom(this);
        break;
      case 'removed':
        if (this.rec.role === 'owner') break;
        pendingNotice = 'The owner removed you from this room.';
        wipeRoom(this);
        break;
      default:
        onCallMessage(this, m);
    }
    renderAll();
  }

  addSys(text) {
    this.msgs.push({ id: newId(), sys: true, text, ts: Date.now() });
    this.saveMsgs();
    renderAll();
  }

  send(text, ttl) {
    const msg = {
      id: newId(), mine: true, text, ts: Date.now(), ttl,
      expires: ttl ? Date.now() + ttl * 1000 : 0, status: 'pending',
    };
    this.msgs.push(msg);
    this.scheduleExpiry(msg);
    if (this.sendPeer({ t: 'msg', id: msg.id, text, ttl, ts: msg.ts })) msg.status = 'sent';
    this.saveMsgs();
    renderAll();
  }

  scheduleExpiry(m) {
    if (!m.expires || this.expiryTimers.has(m.id)) return;
    this.expiryTimers.set(m.id, setTimeout(() => {
      this.msgs = this.msgs.filter((x) => x !== m);
      this.expiryTimers.delete(m.id);
      this.saveMsgs();
      renderAll();
    }, Math.max(0, m.expires - Date.now())));
  }
}

// ---------- ICE configuration from the server ----------

const ice = { iceServers: [], relayOnly: false };

async function loadIceConfig() {
  try {
    const cfg = await (await fetch('/config', { cache: 'no-store', credentials: 'omit' })).json();
    if (Array.isArray(cfg.iceServers)) Object.assign(ice, { iceServers: cfg.iceServers, relayOnly: !!cfg.relayOnly });
  } catch {
    // no config: direct connections only
  }
}

// ---------- pairing: a short one-time code -> a saved room ----------

const CODE_LENGTH = 6;

const pairing = {
  mode: null, // 'create' | 'join'
  code: null,
  keys: null, // our ECDH key pair for this pairing
  secret: null,
  done: false,
  expiresAt: 0,
  timer: null,

  reset() {
    clearInterval(this.timer);
    Object.assign(this, { mode: null, code: null, keys: null, secret: null, done: false });
  },

  format(code) {
    return `${code.slice(0, 3)}-${code.slice(3)}`;
  },

  startCreate() {
    this.reset();
    this.mode = 'create';
    showPairPage('create');
    pairStatus('Getting a code…');
    if (!net.send({ type: 'pair-create' })) {
      pairStatus('Not connected to the server yet. Try again in a moment.', true);
      this.mode = null;
    }
  },

  startJoin(text) {
    const code = text.toUpperCase().replace(/[^A-Z]/g, '');
    if (code.length !== CODE_LENGTH) return pairStatus('Enter all 6 letters of the code.', true);
    this.reset();
    this.mode = 'join';
    this.code = code;
    pairStatus('Connecting…');
    if (!net.send({ type: 'pair-join', code })) {
      pairStatus('Not connected to the server yet. Try again in a moment.', true);
      this.mode = null;
    }
  },

  cancel() {
    if (this.mode && !this.done) net.send({ type: 'pair-end', reason: 'cancelled' });
    this.reset();
  },

  onDisconnect() {
    if (this.mode && !this.done) {
      pairStatus('Lost connection to the server. Please start again.', true);
      this.reset();
    }
  },

  async onServer(m) {
    if (!this.mode) return;
    switch (m.type) {
      case 'pair-created':
        this.code = m.code;
        this.expiresAt = Date.now() + (m.expiresIn || 600000);
        $('pairCode').textContent = this.format(this.code);
        pairStatus('Waiting for the other person to enter the code…');
        this.timer = setInterval(() => this.tick(), 1000);
        this.tick();
        break;
      case 'pair-peer': {
        // Both sides send a fresh ECDH public key; the shared secret never crosses the network.
        pairStatus('Connecting securely…');
        this.keys = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
        this.myPub = new Uint8Array(await crypto.subtle.exportKey('raw', this.keys.publicKey));
        net.send({ type: 'pair-msg', data: JSON.stringify({ pub: b64u.to(this.myPub) }) });
        break;
      }
      case 'pair-msg': {
        let d;
        try {
          d = JSON.parse(m.data);
        } catch {
          return;
        }
        if (!d.pub || !this.keys || this.done) return;
        try {
          const theirPub = b64u.from(d.pub);
          const peerKey = await crypto.subtle.importKey('raw', theirPub, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
          const shared = await crypto.subtle.deriveBits({ name: 'ECDH', public: peerKey }, this.keys.privateKey, 256);
          // Bind the secret to both public keys, in a fixed order.
          const pubs = [hex(this.myPub), hex(theirPub)].sort().join('|');
          const k = await crypto.subtle.importKey('raw', shared, 'HKDF', false, ['deriveBits']);
          const bits = await crypto.subtle.deriveBits(
            { name: 'HKDF', hash: 'SHA-256', salt: te.encode('ghostline-v2'), info: te.encode('room-secret|' + pubs) }, k, 256);
          this.secret = b64u.to(bits);
        } catch {
          net.send({ type: 'pair-end', reason: 'failed' });
          pairStatus('Pairing failed. Please get a new code.', true);
          this.mode = null;
          return;
        }
        this.done = true;
        clearInterval(this.timer);
        net.send({ type: 'pair-end', reason: 'done' });
        showPairPage('name');
        pairStatus('');
        $('roomNameInput').value = '';
        $('roomNameInput').focus();
        break;
      }
      case 'pair-ended': {
        if (this.done) return;
        const reasons = {
          'no-such-code': 'No room is waiting for that code. Check the letters, or ask for a new code.',
          'too-many-attempts': 'Too many attempts from this network. Wait 10 minutes and try again.',
          expired: 'This code expired. Get a new one.',
          'peer-gone': 'The other person closed the pairing screen.',
          cancelled: 'The other person cancelled.',
          failed: 'Pairing failed. Please get a new code.',
          busy: 'The server is busy. Try again in a minute.',
        };
        pairStatus(reasons[m.reason] || 'Pairing ended.', true);
        if (this.mode === 'create') $('pairCode').textContent = '— — —';
        clearInterval(this.timer);
        $('pairExpiry').textContent = '';
        this.mode = null;
        break;
      }
    }
  },

  tick() {
    const left = Math.max(0, Math.round((this.expiresAt - Date.now()) / 1000));
    $('pairExpiry').textContent = `Expires in ${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
  },
};

// role: 'owner' (created the room) or 'participant' (joined with the code).
async function addRoom(secret, name, role) {
  const keys = await roomKeys(secret);
  if (rooms.has(keys.id)) return rooms.get(keys.id);
  let cert = null;
  try {
    cert = await RTCPeerConnection.generateCertificate({ name: 'ECDSA', namedCurve: 'P-256' });
    await store.raw('put', 'cert:' + keys.id, cert);
  } catch {
    // can't store certificates in this browser: the safety code will change on each reconnect
  }
  const rec = {
    id: keys.id, secret, name: name || 'Room', created: Date.now(),
    verifiedCode: null, unread: 0, ended: false, role: role || 'owner',
  };
  const room = new Room(rec, keys, [], cert);
  room.addSys('Paired. Compare the safety code with the other person once (read it out loud) and tap Mark verified.');
  rooms.set(room.id, room);
  saveRooms();
  room.join();
  return room;
}

// Remove the room and everything it stores from this device.
function wipeRoom(room) {
  if (call.room === room) endCall(true);
  room.teardown();
  for (const t of room.expiryTimers.values()) clearTimeout(t);
  net.send({ type: 'leave', room: room.id });
  rooms.delete(room.id);
  saveRooms();
  store.del('msgs:' + room.id);
  store.del('cert:' + room.id);
  if (current === room) showHome();
}

// Keep the room as a read-only archive (history stays, no more chatting).
function archiveRoom(room, sysText) {
  if (call.room === room) endCall(true);
  room.rec.ended = true;
  room.addSys(sysText);
  saveRooms();
  net.send({ type: 'leave', room: room.id });
  room.teardown();
  renderAll();
}

// Participant leaves (or deletes a finished room): gone here, the other side keeps an archive.
async function leaveRoom(room) {
  room.sendPeer({ t: 'bye' });
  await sleep(150); // let the goodbye go out before the channel closes
  wipeRoom(room);
}

// Owner: end the room for both people, history wiped on both sides.
async function endRoomForBoth(room) {
  room.sendPeer({ t: 'end-room' });
  await sleep(150);
  wipeRoom(room);
}

// Owner: remove the participant. They're kicked; the owner keeps the history as an archive.
async function removeParticipant(room) {
  room.sendPeer({ t: 'removed' });
  await sleep(150);
  archiveRoom(room, 'You removed the participant. To talk again, start a new room.');
}

// ---------- calls (one at a time, in any room) ----------

const call = {
  room: null,
  state: 'idle', // idle | outgoing | incoming | active
  local: null,
  myVideo: false,
  peerVideo: false,
  facing: 'user',
};

function onCallMessage(room, m) {
  switch (m.t) {
    case 'ring':
      if (call.state !== 'idle') return room.sendPeer({ t: 'busy' });
      Object.assign(call, { room, state: 'incoming', peerVideo: !!m.video });
      break;
    case 'accept':
      if (call.room !== room || call.state !== 'outgoing') return;
      call.peerVideo = !!m.video;
      call.state = 'active';
      attachTracks();
      break;
    case 'decline':
    case 'busy':
      if (call.room !== room || call.state !== 'outgoing') return;
      endCall(false);
      room.addSys(m.t === 'busy' ? 'The other person is busy.' : 'Call declined.');
      break;
    case 'cancel':
    case 'end':
      if (call.room !== room || call.state === 'idle') return;
      if (call.state === 'incoming') room.addSys('Missed call.');
      endCall(false);
      break;
    case 'video':
      if (call.room === room) call.peerVideo = !!m.on;
      break;
  }
  renderAll();
}

function mediaErrorText(err) {
  const hints = {
    NotAllowedError: 'Permission was blocked. Allow the microphone/camera for this site.',
    NotReadableError: 'The microphone or camera is busy, or this browser can’t use it on this device. ' +
      'On some Android phones only the Ghostline app can use the microphone.',
    NotFoundError: 'No microphone or camera was found.',
  };
  return `Could not start the microphone/camera (${err?.name || 'Error'}). ${hints[err?.name] || 'Try another browser.'}`;
}

async function getMedia(video) {
  const audio = { echoCancellation: true, noiseSuppression: true, autoGainControl: true };
  try {
    call.local = await navigator.mediaDevices.getUserMedia({
      audio, video: video ? { facingMode: call.facing, width: { ideal: 1280 }, height: { ideal: 720 } } : false,
    });
    call.myVideo = video;
  } catch (err) {
    if (!video || err.name === 'NotAllowedError') throw err;
    call.local = await navigator.mediaDevices.getUserMedia({ audio }); // camera failed: voice only
    call.myVideo = false;
  }
  $('localVideo').srcObject = call.local;
}

// Reuse existing transceivers so repeated calls don't grow the connection.
async function attachTracks() {
  const pc = call.room?.pc;
  if (!call.local || !pc) return;
  for (const track of call.local.getTracks()) {
    const tr = pc.getTransceivers().find((t) => t.receiver.track.kind === track.kind && !t.sender.track && !t.stopped);
    if (tr) {
      await tr.sender.replaceTrack(track);
      tr.sender.setStreams?.(call.local);
      if (tr.direction !== 'sendrecv') tr.direction = 'sendrecv';
    } else if (!pc.getSenders().some((s) => s.track === track)) {
      pc.addTrack(track, call.local);
    }
  }
}

function attachRemote() {
  const v = $('remoteVideo');
  if (call.room && v.srcObject !== call.room.remote) v.srcObject = call.room.remote;
  v.play().catch(() => {});
}

async function startCall(video) {
  const room = current;
  if (!room || call.state !== 'idle' || !room.dcOpen()) return;
  try {
    await getMedia(video);
  } catch (err) {
    return room.addSys(mediaErrorText(err));
  }
  Object.assign(call, { room, state: 'outgoing', peerVideo: false });
  room.sendPeer({ t: 'ring', video: call.myVideo });
  attachRemote();
  renderAll();
}

async function acceptCall(video) {
  const room = call.room;
  if (!room || call.state !== 'incoming') return;
  openRoom(room);
  try {
    await getMedia(video);
  } catch (err) {
    room.addSys(mediaErrorText(err));
    return declineCall();
  }
  call.state = 'active';
  await attachTracks();
  room.sendPeer({ t: 'accept', video: call.myVideo });
  attachRemote();
  renderAll();
}

function declineCall() {
  call.room?.sendPeer({ t: 'decline' });
  endCall(false);
}

function endCall(notify) {
  const room = call.room;
  if (notify && room && call.state !== 'idle') room.sendPeer({ t: call.state === 'outgoing' ? 'cancel' : 'end' });
  call.local?.getTracks().forEach((t) => t.stop());
  room?.pc?.getSenders().forEach((s) => s.track && s.replaceTrack(null).catch(() => {}));
  Object.assign(call, { room: null, state: 'idle', local: null, myVideo: false, peerVideo: false });
  $('localVideo').srcObject = null;
  renderAll();
}

function toggleMic() {
  const mic = call.local?.getAudioTracks()[0];
  if (!mic) return;
  mic.enabled = !mic.enabled;
  renderAll();
}

async function toggleCam() {
  if (!call.local) return;
  let cam = call.local.getVideoTracks()[0];
  if (!cam) {
    try {
      const s = await navigator.mediaDevices.getUserMedia({ video: { facingMode: call.facing } });
      cam = s.getVideoTracks()[0];
      call.local.addTrack(cam);
      $('localVideo').srcObject = call.local;
      if (call.state === 'active') await attachTracks();
    } catch (err) {
      return call.room?.addSys(mediaErrorText(err));
    }
    call.myVideo = true;
  } else {
    call.myVideo = !call.myVideo;
    cam.enabled = call.myVideo;
  }
  call.room?.sendPeer({ t: 'video', on: call.myVideo });
  renderAll();
}

async function flipCam() {
  const old = call.local?.getVideoTracks()[0];
  if (!old) return;
  call.facing = call.facing === 'user' ? 'environment' : 'user';
  old.stop(); // many phones can't open two cameras at once
  call.local.removeTrack(old);
  try {
    const s = await navigator.mediaDevices.getUserMedia({ video: { facingMode: call.facing } });
    const cam = s.getVideoTracks()[0];
    call.local.addTrack(cam);
    $('localVideo').srcObject = call.local;
    const sender = call.room?.pc?.getSenders().find((x) => x.track === old);
    if (sender) await sender.replaceTrack(cam);
    else if (call.state === 'active') await attachTracks();
  } catch {
    call.room?.addSys('Could not switch camera.');
  }
  renderAll();
}

// ---------- UI ----------

const pages = ['home', 'pair', 'room'];
function showPage(id) {
  for (const p of pages) $(p).hidden = p !== id;
}

function showHome() {
  current = null;
  $('menu').hidden = true;
  showPage('home');
  renderAll();
  if (pendingNotice) {
    const text = pendingNotice;
    pendingNotice = null;
    ask({ title: 'Room closed', text, ok: 'OK' });
  }
}

function showPairPage(step) {
  showPage('pair');
  $('pairTitle').textContent = step === 'join' ? 'Join with code' : step === 'name' ? 'Room ready' : 'New room';
  $('pairShow').hidden = step !== 'create';
  $('pairEnter').hidden = step !== 'join';
  $('pairName').hidden = step !== 'name';
  if (step === 'create') {
    $('pairCode').textContent = '— — —';
    $('pairExpiry').textContent = '';
  }
}

function pairStatus(text, bad = false) {
  $('pairStatus').textContent = text;
  $('pairStatus').classList.toggle('bad', bad);
}

function openRoom(room) {
  current = room;
  room.rec.unread = 0;
  saveRooms();
  $('menu').hidden = true;
  showPage('room');
  renderedMsgs = null;
  renderAll();
  scrollChat();
}

function timeText(ts) {
  const d = new Date(ts);
  return new Date().toDateString() === d.toDateString()
    ? d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : d.toLocaleDateString([], { month: 'short', day: 'numeric' });
}

function roomStatus(room) {
  if (room.rec.ended) return ['The other person left', 'bad'];
  if (room.full) return ['Open in another tab or device', 'bad'];
  if (room.dcOpen()) {
    const verified = room.code && room.code === room.rec.verifiedCode;
    return ['Online · end-to-end encrypted' + (verified ? ' · verified' : ''), 'ok'];
  }
  if (room.serverPeer) return ['Connecting…', ''];
  if (!net.open()) return ['Reconnecting to the server…', ''];
  return ['Offline · messages will be sent when they’re back', 'off'];
}

const lastTs = (room) => (room.msgs.length ? room.msgs[room.msgs.length - 1].ts : room.rec.created);

function renderList() {
  const sorted = [...rooms.values()].sort((a, b) => lastTs(b) - lastTs(a));
  $('roomList').replaceChildren(...sorted.map((room) => {
    const li = document.createElement('li');
    const btn = document.createElement('button');
    btn.className = 'room-item';
    btn.onclick = () => openRoom(room);
    const dot = document.createElement('span');
    dot.className = 'dot ' + (room.rec.ended ? 'bad' : room.dcOpen() ? 'ok' : 'off');
    const body = document.createElement('span');
    body.className = 'room-body';
    const name = document.createElement('strong');
    name.textContent = room.rec.name;
    const last = [...room.msgs].reverse().find((m) => !m.sys);
    const preview = document.createElement('span');
    preview.className = 'preview';
    preview.textContent = room.rec.ended
      ? 'The other person left'
      : last ? (last.mine ? 'You: ' : '') + last.text : 'No messages yet';
    body.append(name, preview);
    btn.append(dot, body);
    if (room.rec.unread) {
      const badge = document.createElement('span');
      badge.className = 'badge';
      badge.textContent = room.rec.unread > 99 ? '99+' : room.rec.unread;
      btn.append(badge);
    }
    li.append(btn);
    return li;
  }));
  $('emptyRooms').hidden = rooms.size > 0;
  $('netStatus').textContent = net.open() ? '' : 'Connecting…';
}

let renderedMsgs = null;
function renderMessages(room) {
  const box = $('messages');
  const atBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 60;
  const key = room.id + ':' + room.msgs.map((m) => m.id + (m.status || '')).join(',');
  if (key === renderedMsgs) return;
  renderedMsgs = key;
  box.replaceChildren(...room.msgs.map((m) => {
    const el = document.createElement('div');
    if (m.sys) {
      el.className = 'sys';
      el.textContent = m.text;
      return el;
    }
    el.className = 'msg ' + (m.mine ? 'me' : 'them');
    el.textContent = m.text;
    const t = document.createElement('time');
    const tick = m.mine ? { pending: ' · 🕓 waiting', sent: ' · ✓', delivered: ' · ✓✓' }[m.status] || '' : '';
    t.textContent = timeText(m.ts) + tick +
      (m.ttl ? ` · disappears in ${m.ttl >= 60 ? m.ttl / 60 + 'm' : m.ttl + 's'}` : '');
    el.append(t);
    return el;
  }));
  if (atBottom) scrollChat();
}

function scrollChat() {
  const box = $('messages');
  box.scrollTop = box.scrollHeight;
}

function renderRoom() {
  const room = current;
  if (!room) return;
  if (!rooms.has(room.id)) return showHome();
  $('roomTitle').textContent = room.rec.name;
  const [text, kind] = roomStatus(room);
  $('statusText').textContent = text;
  $('dot').className = 'dot ' + kind;
  const ready = room.dcOpen();
  $('voiceBtn').disabled = !ready || call.state !== 'idle';
  $('videoBtn').disabled = !ready || call.state !== 'idle';
  $('msgInput').disabled = room.rec.ended;
  $('sendBtn').disabled = room.rec.ended;
  $('msgInput').placeholder = room.rec.ended ? 'This room has ended' : ready ? 'Message' : 'Message (sent when they’re online)';

  const verified = !!room.code && room.code === room.rec.verifiedCode;
  $('safety').textContent = room.code || '—';
  $('safetyBox').classList.toggle('verified', verified);
  $('safetyBox').classList.toggle('changed', !!room.rec.verifiedCode && !!room.code && !verified);
  $('verifyBtn').hidden = !room.code || verified;
  const owner = room.rec.role === 'owner';
  $('roleTag').textContent = owner ? 'You own this room' : 'You joined this room';
  $('removeBtn').hidden = !owner || room.rec.ended;
  $('leaveBtn').textContent = room.rec.ended ? 'Delete room' : owner ? 'End room for both' : 'Leave room';
  renderMessages(room);
}

function renderCall() {
  const here = call.room && call.room === current;
  const inCall = call.state === 'outgoing' || call.state === 'active';
  $('call').hidden = !(here && inCall);
  $('callBar').hidden = !(inCall && !here);
  $('incoming').hidden = call.state !== 'incoming';
  if (call.state === 'incoming') {
    $('incomingText').textContent = `Incoming ${call.peerVideo ? 'video' : 'voice'} call · ${call.room.rec.name}`;
  }
  $('callLabel').textContent = call.state === 'outgoing' ? 'Calling…' : 'Voice call';
  const showRemote = call.state === 'active' && call.peerVideo;
  $('remoteVideo').style.visibility = showRemote ? 'visible' : 'hidden';
  $('avatar').hidden = showRemote;
  $('localVideo').hidden = !call.myVideo;
  $('localVideo').classList.toggle('back', call.facing === 'environment');
  $('camBtn').textContent = call.myVideo ? 'Camera off' : 'Camera on';
  $('flipBtn').hidden = !call.myVideo;
  const mic = call.local?.getAudioTracks()[0];
  $('micBtn').textContent = mic && !mic.enabled ? 'Unmute' : 'Mute';
}

function renderAll() {
  if (!$('home').hidden) renderList();
  renderRoom();
  renderCall();
}

// A small promise-based dialog for confirmations and text input.
function ask({ title, text = '', input = null, ok = 'OK', danger = false }) {
  const d = $('dialog');
  $('dialogTitle').textContent = title;
  $('dialogText').textContent = text;
  $('dialogInput').hidden = input === null;
  $('dialogInput').value = input ?? '';
  $('dialogOk').textContent = ok;
  $('dialogOk').className = danger ? 'danger-fill' : 'primary';
  d.showModal();
  if (input !== null) $('dialogInput').focus();
  return new Promise((resolve) => {
    const done = (value) => {
      $('dialogOk').onclick = $('dialogCancel').onclick = d.oncancel = null;
      d.close();
      resolve(value);
    };
    $('dialogOk').onclick = () => done(input === null ? true : $('dialogInput').value.trim());
    $('dialogCancel').onclick = () => done(null);
    d.oncancel = () => done(null);
  });
}

// ---------- wiring ----------

$('newRoomBtn').onclick = () => pairing.startCreate();
$('joinRoomBtn').onclick = () => {
  pairing.reset();
  showPairPage('join');
  pairStatus('');
  $('codeInput').value = '';
  $('codeInput').focus();
};
$('pairBack').onclick = () => {
  pairing.cancel();
  showHome();
};
$('codeInput').oninput = () => {
  const c = $('codeInput').value.toUpperCase().replace(/[^A-Z]/g, '').slice(0, CODE_LENGTH);
  $('codeInput').value = c.length > 3 ? `${c.slice(0, 3)}-${c.slice(3)}` : c;
};
$('pairEnter').onsubmit = (e) => {
  e.preventDefault();
  pairing.startJoin($('codeInput').value);
};
$('pairName').onsubmit = async (e) => {
  e.preventDefault();
  if (!pairing.secret) return;
  const role = pairing.mode === 'create' ? 'owner' : 'participant';
  const room = await addRoom(pairing.secret, $('roomNameInput').value.trim() || 'Room', role);
  pairing.reset();
  openRoom(room);
};

$('roomBack').onclick = showHome;
$('menuBtn').onclick = () => ($('menu').hidden = !$('menu').hidden);
$('renameBtn').onclick = async () => {
  $('menu').hidden = true;
  const room = current;
  const name = await ask({ title: 'Rename room', text: 'Only you see this name.', input: room.rec.name, ok: 'Save' });
  if (name) {
    room.rec.name = name.slice(0, 40);
    saveRooms();
    renderAll();
  }
};
$('clearBtn').onclick = async () => {
  $('menu').hidden = true;
  const room = current;
  const sure = await ask({
    title: 'Clear chat?',
    text: room.dcOpen()
      ? 'Messages are deleted on both devices.'
      : 'The other person is offline, so only your copy is deleted.',
    ok: 'Clear',
    danger: true,
  });
  if (!sure) return;
  room.msgs = [];
  room.saveMsgs();
  room.sendPeer({ t: 'clear' });
  renderAll();
};
$('removeBtn').onclick = async () => {
  $('menu').hidden = true;
  const room = current;
  if (room.rec.role !== 'owner' || room.rec.ended) return;
  const sure = await ask({
    title: 'Remove the participant?',
    text: 'They are removed from this room and their copy is deleted. ' +
      (room.dcOpen() ? '' : 'They are offline, so this takes effect when they next connect. ') +
      'You keep the chat history here as a read-only archive.',
    ok: 'Remove',
    danger: true,
  });
  if (sure) removeParticipant(room);
};
$('leaveBtn').onclick = async () => {
  $('menu').hidden = true;
  const room = current;
  const owner = room.rec.role === 'owner';
  let opts;
  if (room.rec.ended) {
    opts = { title: 'Delete this room?', text: 'Its chat history is deleted from this device.', ok: 'Delete', danger: true };
  } else if (owner) {
    opts = {
      title: 'End this room for both?',
      text: 'The room and its chat history are deleted on both devices. ' +
        (room.dcOpen() ? '' : 'The other person is offline, so this reaches them when they next connect. ') +
        'This cannot be undone.',
      ok: 'End for both',
      danger: true,
    };
  } else {
    opts = {
      title: 'Leave this room?',
      text: 'The room and its chat history are deleted from this device. ' +
        (room.dcOpen() ? 'The owner is told you left. ' : 'The owner is offline, so they’ll just see you as offline. ') +
        'To talk again you’ll need a new code.',
      ok: 'Leave',
      danger: true,
    };
  }
  const sure = await ask(opts);
  if (!sure) return;
  if (!room.rec.ended && owner) endRoomForBoth(room);
  else leaveRoom(room);
};
$('verifyBtn').onclick = () => {
  current.rec.verifiedCode = current.code;
  saveRooms();
  current.addSys('Safety code marked as verified. You will be warned if it changes.');
};

$('composer').onsubmit = (e) => {
  e.preventDefault();
  const room = current;
  const text = $('msgInput').value.trim();
  if (!room || !text || room.rec.ended) return;
  room.send(text, Number($('ttl').value) || 0);
  $('msgInput').value = '';
  scrollChat();
};

$('voiceBtn').onclick = () => startCall(false);
$('videoBtn').onclick = () => startCall(true);
$('acceptVoiceBtn').onclick = () => acceptCall(false);
$('acceptVideoBtn').onclick = () => acceptCall(true);
$('declineBtn').onclick = declineCall;
$('hangupBtn').onclick = () => endCall(true);
$('micBtn').onclick = toggleMic;
$('camBtn').onclick = toggleCam;
$('flipBtn').onclick = flipCam;
$('callBar').onclick = () => call.room && openRoom(call.room);

// Blank the page while it is in the background, so app-switcher thumbnails stay empty.
document.addEventListener('visibilitychange', () => {
  document.body.classList.toggle('veil', document.hidden);
  if (!document.hidden && current) {
    current.rec.unread = 0;
    saveRooms();
    renderAll();
  }
});
window.addEventListener('pagehide', () => {
  for (const r of rooms.values()) r.teardown();
  net.ws?.close();
});

// ---------- boot ----------

(async () => {
  // Old-style invite links are no longer used; don't leave a secret in the address bar.
  if (location.hash) history.replaceState(null, '', location.pathname);
  if (!window.isSecureContext || !crypto.subtle) {
    showPage('home');
    $('newRoomBtn').disabled = $('joinRoomBtn').disabled = true;
    $('emptyRooms').hidden = false;
    $('emptyRooms').textContent = 'This page must be opened over HTTPS (or on localhost) for encryption to work.';
    return;
  }
  await store.open();
  $('memoryOnly').hidden = !!store.db;
  await loadIceConfig();
  for (const rec of (await store.get('rooms')) || []) {
    try {
      rooms.set(rec.id, await Room.load(rec));
    } catch (err) {
      console.error('could not load room', err);
    }
  }
  showHome();
  net.connect();
})();
```


## `test/server.test.js`

<a id="testservertestjs"></a>

```javascript
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
  const { code } = await a.next('pair-created');
  assert.match(code, /^[A-HJ-NP-Z]{6}$/); // 6 letters, no I or O
  b.send({ type: 'pair-join', code: code.toLowerCase().replace(/(...)/, '$1-') }); // case and dash don't matter
  await a.next('pair-peer'); await b.next('pair-peer');
  a.send({ type: 'pair-msg', data: 'pA' });
  assert.strictEqual((await b.next('pair-msg')).data, 'pA');
  b.send({ type: 'pair-msg', data: 'pB' });
  assert.strictEqual((await a.next('pair-msg')).data, 'pB');

  // the code can't be claimed twice
  const c = client(); await c.open();
  c.send({ type: 'pair-join', code });
  assert.strictEqual((await c.next('pair-ended')).reason, 'no-such-code');

  b.send({ type: 'pair-end', reason: 'done' });
  assert.strictEqual((await a.next('pair-ended')).reason, 'done');
  c.send({ type: 'pair-join', code });
  assert.strictEqual((await c.next('pair-ended')).reason, 'no-such-code');
  for (const x of [a, b, c]) x.ws.close();
});

test('pairing: creator leaving ends it for the joiner', async () => {
  const a = client(), b = client();
  await Promise.all([a.open(), b.open()]);
  a.send({ type: 'pair-create' });
  const { code } = await a.next('pair-created');
  b.send({ type: 'pair-join', code });
  await b.next('pair-peer');
  a.ws.close();
  assert.strictEqual((await b.next('pair-ended')).reason, 'peer-gone');
  b.ws.close();
});

test('pairing: limits code attempts per address', async () => {
  const c = client(); await c.open();
  const reasons = [];
  for (let i = 0; i < 12; i++) {
    c.send({ type: 'pair-join', code: 'IIIIII' }); // never a valid code
    reasons.push((await c.next('pair-ended')).reason);
  }
  assert.ok(reasons.includes('too-many-attempts'));
  c.ws.close();
});
```


## `android/.gitignore`

<a id="androidgitignore"></a>

```gitignore
.gradle/
build/
app/build/
local.properties
*.apk
*.aab
.idea/
*.iml
keystore.properties
```


## `android/app/build.gradle.kts`

<a id="androidappbuildgradlekts"></a>

```kotlin
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from android/keystore.properties, which is kept out of git. Without it
// the release build is left unsigned.
val signing = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "app.plainnotes"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.plainnotes"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signing != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Google's WebRTC for Android. Its audio module records with AudioRecord in 16-bit PCM,
    // which works on phones whose audio driver rejects the float capture browsers ask for.
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
```


## `android/app/src/main/AndroidManifest.xml`

<a id="androidappsrcmainandroidmanifestxml"></a>

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.VIBRATE" />

    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher"
        android:label="Notes"
        android:networkSecurityConfig="@xml/network_security_config"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize"
            android:exported="true"
            android:launchMode="singleTask"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <!-- "Share" an invite link from another app into Ghostline. -->
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```


## `android/app/src/main/java/app/plainnotes/CallAudio.kt`

<a id="androidappsrcmainjavaappplainnotescallaudiokt"></a>

```kotlin
package app.plainnotes

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

// Puts the phone in call mode and routes call audio to the loudspeaker or earpiece.
// Main thread only.
@Suppress("DEPRECATION")
class CallAudio(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    var speaker = true
        private set

    fun start() {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        route()
    }

    fun toggleSpeaker(): Boolean {
        speaker = !speaker
        route()
        return speaker
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else am.isSpeakerphoneOn = false
        am.mode = AudioManager.MODE_NORMAL
    }

    private fun route() {
        if (Build.VERSION.SDK_INT >= 31) {
            val type = if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            am.availableCommunicationDevices.firstOrNull { it.type == type }?.let { am.setCommunicationDevice(it) }
        } else {
            am.isSpeakerphoneOn = speaker
        }
    }
}
```


## `android/app/src/main/java/app/plainnotes/Crypto.kt`

<a id="androidappsrcmainjavaappplainnotescryptokt"></a>

```kotlin
package app.plainnotes

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Must match public/app.js exactly, so the app and the web version can share a room.

object Crypto {
    private val rng = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { rng.nextBytes(it) }
    fun b64u(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun unb64u(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    fun newId() = hex(randomBytes(8))

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    // HKDF-SHA256 (RFC 5869), the same as WebCrypto's HKDF deriveBits.
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < len) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(len)
    }
}

// From the invite link's secret: the room id (the only thing the server sees) and the
// AES-256-GCM key that encrypts all signaling.
class RoomKeys(secret: String) {
    val roomId: String
    private val key: SecretKeySpec
    private val aad: ByteArray

    init {
        val raw = Crypto.unb64u(secret)
        require(raw.size >= 16) { "bad secret" }
        val salt = "ghostline-v1".toByteArray()
        roomId = Crypto.hex(Crypto.hkdf(raw, salt, "room-id".toByteArray(), 32))
        key = SecretKeySpec(Crypto.hkdf(raw, salt, "signal-key".toByteArray(), 32), "AES")
        aad = roomId.toByteArray()
    }

    fun seal(plain: String): String {
        val iv = Crypto.randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return Crypto.b64u(iv) + "." + Crypto.b64u(c.doFinal(plain.toByteArray()))
    }

    // null if tampered with or not from our peer
    fun unseal(sealed: String): String? = try {
        val parts = sealed.split('.')
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Crypto.unb64u(parts[0])))
        c.updateAAD(aad)
        String(c.doFinal(Crypto.unb64u(parts[1])))
    } catch (e: Exception) {
        null
    }
}

// The safety code both sides show; must equal updateSafetyCode() in public/app.js.
fun safetyCode(localSdp: String?, remoteSdp: String?): String? {
    val re = Regex("a=fingerprint:\\S+ (\\S+)")
    val a = localSdp?.let { re.find(it)?.groupValues?.get(1)?.uppercase() } ?: return null
    val b = remoteSdp?.let { re.find(it)?.groupValues?.get(1)?.uppercase() } ?: return null
    val h = Crypto.sha256(listOf(a, b).sorted().joinToString("|").toByteArray())
    return (0..3).joinToString(" ") { i ->
        val n = ((h[i * 2].toInt() and 0xff) shl 8) or (h[i * 2 + 1].toInt() and 0xff)
        "%04d".format(n % 10000)
    }
}

// Pairing with a 6-letter code: each device sends a fresh P-256 ECDH public key; both derive
// the same room secret. Must match the pairing code in public/app.js.
object Pairing {
    const val CODE_LENGTH = 6

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun pad32(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    // uncompressed point, the same as WebCrypto's "raw" export
    fun rawPublic(key: ECPublicKey): ByteArray = byteArrayOf(4) + pad32(key.w.affineX) + pad32(key.w.affineY)

    fun roomSecret(myPrivate: PrivateKey, myPublic: ECPublicKey, theirRaw: ByteArray): String {
        require(theirRaw.size == 65 && theirRaw[0] == 4.toByte()) { "bad public key" }
        val params = myPublic.params
        val x = BigInteger(1, theirRaw.copyOfRange(1, 33))
        val y = BigInteger(1, theirRaw.copyOfRange(33, 65))
        val p = (params.curve.field as ECFieldFp).p
        require(x < p && y < p && y.modPow(BigInteger.TWO, p) == (x.pow(3) + params.curve.a * x + params.curve.b).mod(p)) {
            "point not on curve"
        }
        val theirs = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(myPrivate)
            doPhase(theirs, true)
            generateSecret()
        }
        // bind the secret to both public keys, in a fixed order
        val pubs = listOf(Crypto.hex(rawPublic(myPublic)), Crypto.hex(theirRaw)).sorted().joinToString("|")
        return Crypto.b64u(Crypto.hkdf(shared, "ghostline-v2".toByteArray(), "room-secret|$pubs".toByteArray(), 32))
    }

    fun normalizeCode(text: String) = text.uppercase().filter { it in 'A'..'Z' }
    fun formatCode(code: String) = if (code.length > 3) code.take(3) + "-" + code.drop(3) else code
}
```


## `android/app/src/main/java/app/plainnotes/Hub.kt`

<a id="androidappsrcmainjavaappplainnoteshubkt"></a>

```kotlin
package app.plainnotes

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtcCertificatePem
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey

enum class CallState { IDLE, OUTGOING, INCOMING, ACTIVE }

// ---------- what the screen shows (immutable snapshots, built on the hub thread) ----------

data class MsgView(val id: String, val mine: Boolean, val sys: Boolean, val text: String, val ts: Long, val ttl: Int, val status: String)

data class RoomView(
    val id: String, val name: String, val status: String, val kind: String, val ready: Boolean,
    val ended: Boolean, val unread: Int, val preview: String, val lastTs: Long,
    val code: String?, val verified: Boolean, val codeChanged: Boolean, val owner: Boolean,
    val msgs: List<MsgView>, val msgsKey: String,
)

data class PairView(val step: String?, val code: String?, val expiresAt: Long, val status: String, val bad: Boolean)
data class CallView(val roomId: String?, val state: CallState, val peerVideo: Boolean, val myVideo: Boolean, val frontCamera: Boolean)
data class HubView(val connected: Boolean, val rooms: List<RoomView>, val pair: PairView, val call: CallView)

interface HubUi {
    fun onView(view: HubView) // main thread
    fun onNotice(text: String) // a one-off message, e.g. "the owner removed you"
}

// All rooms, pairing and calls. Speaks the same protocol as public/app.js. All state lives on
// one background thread (exec), touched in order, like the web app's promise queue.
class Hub(
    context: Context,
    private val origin: String,
    private val ui: HubUi,
    private val audio: CallAudio,
    private val egl: EglBase,
    private val remoteSink: VideoSink,
    private val localSink: VideoSink,
    dataKey: SecretKey? = null, // with the Notes disguise on, the key the PIN unwrapped
) {
    private val appContext = context.applicationContext
    private val store = Store(appContext, dataKey)
    private val closedLatch = CountDownLatch(1)
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()

    private val adm = JavaAudioDeviceModule.builder(appContext)
        .setUseHardwareAcousticEchoCanceler(true)
        .setUseHardwareNoiseSuppressor(true)
        .createAudioDeviceModule()
    // Video codecs are needed even for voice: the other side may turn its camera on mid-call.
    private val factory = PeerConnectionFactory.builder()
        .setAudioDeviceModule(adm)
        .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
        .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
        .createPeerConnectionFactory()

    private val rooms = LinkedHashMap<String, Room>()
    private var ws: WebSocket? = null
    private var wsOpen = false
    private var backoffMs = 1000L
    private var closed = false
    private var iceServers = listOf<PeerConnection.IceServer>()
    private var relayOnly = false
    private var openRoomId: String? = null
    private var foreground = true

    // ---------- public API (any thread) ----------

    fun start() = post {
        loadRooms()
        loadConfig()
        connect()
        publish()
    }

    fun setOpenRoom(id: String?) = post {
        openRoomId = id
        rooms[id]?.let { if (it.rec.unread != 0) { it.rec.unread = 0; saveRooms() } }
        publish()
    }

    fun setForeground(on: Boolean) = post {
        foreground = on
        if (on) rooms[openRoomId]?.let { it.rec.unread = 0; saveRooms(); publish() }
    }

    fun send(roomId: String, text: String, ttl: Int) = post { rooms[roomId]?.send(text, ttl) }
    fun clearChat(roomId: String) = post {
        val r = rooms[roomId] ?: return@post
        r.msgs.clear()
        r.saveMsgs()
        r.sendPeer(JSONObject().put("t", "clear"))
        publish()
    }
    fun rename(roomId: String, name: String) = post { rooms[roomId]?.let { it.rec.name = name.take(40); saveRooms(); publish() } }
    fun verify(roomId: String) = post {
        val r = rooms[roomId] ?: return@post
        r.rec.verifiedCode = r.code
        saveRooms()
        r.addSys("Safety code marked as verified. You will be warned if it changes.")
    }
    fun leave(roomId: String) = post { leaveNow(roomId) }
    fun endRoom(roomId: String) = post { endRoomForBothNow(roomId) }
    fun removeParticipant(roomId: String) = post { removeParticipantNow(roomId) }

    fun pairCreate() = post { pairing.create() }
    fun pairJoin(code: String) = post { pairing.join(code) }
    fun pairCancel() = post { pairing.cancel() }
    fun pairSave(name: String, then: (String) -> Unit) = post {
        val id = pairing.save(name) ?: return@post
        main.post { then(id) }
    }

    fun startCall(roomId: String, video: Boolean) = post { startCallNow(roomId, video) }
    fun acceptCall(video: Boolean) = post { acceptCallNow(video) }
    fun declineCall() = post {
        call.room?.sendPeer(JSONObject().put("t", "decline"))
        endCall(false)
    }
    fun hangUp() = post { endCall(true) }
    fun setMuted(muted: Boolean) = post { audioTrack?.setEnabled(!muted) }
    fun setCamera(on: Boolean) = post { setCameraNow(on) }
    fun flipCamera() = post {
        val cam = camera ?: return@post
        cam.switchCamera(null)
        frontCamera = !frontCamera
        publish()
    }

    fun close() {
        post {
            endCall(true)
            for (r in rooms.values) r.teardown()
            ws?.close(1000, null)
            ws = null
            closed = true
            factory.dispose()
            adm.release()
            closedLatch.countDown()
        }
        exec.shutdown()
        http.dispatcher.executorService.shutdown()
    }

    // Blocks until close() has finished, including any saves queued before it.
    fun awaitClosed() = closedLatch.await(10, TimeUnit.SECONDS)

    // ---------- plumbing ----------

    private fun post(block: () -> Unit) {
        if (exec.isShutdown) return
        exec.execute {
            if (closed) return@execute
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "hub error", e)
            }
        }
    }

    private fun later(ms: Long, block: () -> Unit): ScheduledFuture<*>? =
        if (exec.isShutdown) null else exec.schedule({ if (!closed) try { block() } catch (e: Exception) { Log.e(TAG, "hub error", e) } }, ms, TimeUnit.MILLISECONDS)

    private var publishPending = false
    private fun publish() {
        if (publishPending) return
        publishPending = true
        later(30) {
            publishPending = false
            val v = view()
            main.post { if (!closed) ui.onView(v) }
        }
    }

    private fun sdp(op: (SdpObserver) -> Unit) {
        val done = CountDownLatch(1)
        var error: String? = null
        op(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {}
            override fun onSetSuccess() = done.countDown()
            override fun onCreateFailure(e: String?) { error = e; done.countDown() }
            override fun onSetFailure(e: String?) { error = e; done.countDown() }
        })
        if (!done.await(10, TimeUnit.SECONDS)) error = "timed out"
        error?.let { throw IllegalStateException(it) }
    }

    // ---------- storage ----------

    private fun loadRooms() {
        val arr = store.read(ROOMS)?.let { JSONArray(it) } ?: JSONArray()
        for (i in 0 until arr.length()) {
            try {
                val rec = RoomRec.from(arr.getJSONObject(i))
                val msgs = store.read("$MSGS${rec.id}")?.let { JSONArray(it) } ?: JSONArray()
                val cert = store.read("$CERT${rec.id}")?.let { JSONObject(it) }
                    ?.let { RtcCertificatePem(it.getString("key"), it.getString("cert")) }
                val room = Room(rec, cert)
                for (j in 0 until msgs.length()) Msg.from(msgs.getJSONObject(j))?.let { room.msgs.add(it) }
                room.msgs.removeAll { it.expires in 1..System.currentTimeMillis() }
                room.msgs.forEach { room.scheduleExpiry(it) }
                rooms[rec.id] = room
            } catch (e: Exception) {
                Log.e(TAG, "could not load a room", e)
            }
        }
    }

    private fun saveRooms() {
        store.write(ROOMS, JSONArray(rooms.values.map { it.rec.json() }).toString())
    }

    // ---------- server connection (one WebSocket for every room) ----------

    private fun loadConfig() {
        try {
            http.newCall(Request.Builder().url("$origin/config").build()).execute().use { res ->
                val j = JSONObject(res.body!!.string())
                val arr = j.optJSONArray("iceServers") ?: JSONArray()
                iceServers = (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    val urls = when (val u = s.opt("urls")) {
                        is JSONArray -> (0 until u.length()).map { u.getString(it) }
                        else -> listOf(u.toString())
                    }
                    PeerConnection.IceServer.builder(urls).apply {
                        if (s.has("username")) setUsername(s.getString("username"))
                        if (s.has("credential")) setPassword(s.getString("credential"))
                    }.createIceServer()
                }
                relayOnly = j.optBoolean("relayOnly")
            }
        } catch (e: Exception) {
            Log.w(TAG, "no ICE config", e)
        }
    }

    private fun connect() {
        val url = origin.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws"
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = post {
                if (ws !== webSocket) return@post
                backoffMs = 1000
                wsOpen = true
                for (r in rooms.values) r.join()
                publish()
            }

            override fun onMessage(webSocket: WebSocket, text: String) = post {
                if (ws === webSocket) onServerMessage(JSONObject(text))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)

            private fun lost(webSocket: WebSocket) = post {
                if (ws !== webSocket) return@post
                ws = null
                wsOpen = false
                for (r in rooms.values) r.serverPeer = false
                pairing.onDisconnect()
                publish()
                later(backoffMs) { if (ws == null) connect() }
                backoffMs = (backoffMs * 2).coerceAtMost(30_000)
            }
        })
    }

    private fun connected() = wsOpen

    private fun sendServer(obj: JSONObject): Boolean = wsOpen && (ws?.send(obj.toString()) ?: false)

    private fun onServerMessage(m: JSONObject) {
        val roomId = m.optString("room")
        if (roomId.isNotEmpty()) rooms[roomId]?.onServer(m) else pairing.onServer(m)
        publish()
    }

    // ---------- a room ----------

    class RoomRec(
        val id: String, val secret: String, var name: String, val created: Long,
        var verifiedCode: String?, var unread: Int, var ended: Boolean,
        val role: String, // "owner" (created it) or "participant" (joined with the code)
    ) {
        fun json(): JSONObject = JSONObject().put("id", id).put("secret", secret).put("name", name).put("created", created)
            .put("verifiedCode", verifiedCode ?: JSONObject.NULL).put("unread", unread).put("ended", ended).put("role", role)

        companion object {
            fun from(j: JSONObject) = RoomRec(
                j.getString("id"), j.getString("secret"), j.optString("name", "Room"), j.optLong("created"),
                if (j.isNull("verifiedCode")) null else j.optString("verifiedCode"), j.optInt("unread"), j.optBoolean("ended"),
                j.optString("role", "owner"),
            )
        }
    }

    class Msg(
        val id: String, val mine: Boolean, val sys: Boolean, val text: String, val ts: Long,
        val ttl: Int, val expires: Long, var status: String,
    ) {
        fun json(): JSONObject = JSONObject().put("id", id).put("mine", mine).put("sys", sys).put("text", text)
            .put("ts", ts).put("ttl", ttl).put("expires", expires).put("status", status)

        fun view() = MsgView(id, mine, sys, text, ts, ttl, status)

        companion object {
            fun from(j: JSONObject): Msg? = try {
                Msg(
                    j.getString("id"), j.optBoolean("mine"), j.optBoolean("sys"), j.getString("text"),
                    j.optLong("ts"), j.optInt("ttl"), j.optLong("expires"), j.optString("status"),
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private inner class Room(val rec: RoomRec, val cert: RtcCertificatePem?) {
        val id = rec.id
        val keys = RoomKeys(rec.secret)
        val msgs = mutableListOf<Msg>()
        var pc: PeerConnection? = null
        var dc: DataChannel? = null
        val pending = mutableListOf<IceCandidate>()
        var polite = false
        var makingOffer = false
        var ignoreOffer = false
        var serverPeer = false
        var full = false
        var code: String? = null

        // Every video track the other side could be sending on. Repeated calls reuse transceivers,
        // and which one carries their camera can change, so the screen is fed from all of them
        // (like the web app's remote stream); only the one in use has frames.
        fun remoteVideos(): List<VideoTrack> = pc?.transceivers.orEmpty()
            .filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO && !it.isStopped }
            .mapNotNull { it.receiver.track() as? VideoTrack }
        private val expiry = HashMap<String, ScheduledFuture<*>?>()
        // replay protection for signaling: fresh id per peer session + increasing counter
        var me = Crypto.newId()
        var seq = 0L
        var peerId: String? = null
        var peerSeq = 0L

        fun saveMsgs() = store.write("$MSGS$id", JSONArray(msgs.map { it.json() }).toString())
        fun join() { if (!rec.ended) sendServer(JSONObject().put("type", "join").put("room", id)) }
        fun dcOpen() = dc?.state() == DataChannel.State.OPEN

        fun resetSession() {
            me = Crypto.newId(); seq = 0; peerId = null; peerSeq = 0
        }

        fun onServer(m: JSONObject) {
            when (m.optString("type")) {
                "joined" -> {
                    full = false
                    resetSession()
                    serverPeer = m.optInt("peers") > 0
                    if (serverPeer) {
                        teardown()
                        polite = true
                        createPeer()
                    }
                }
                "peer-joined" -> {
                    resetSession()
                    teardown()
                    serverPeer = true
                    polite = false
                    setupDc(createPeer().createDataChannel("chat", DataChannel.Init().apply { ordered = true }))
                }
                "peer-left" -> {
                    serverPeer = false
                    if (!dcOpen()) teardown() // the direct connection may outlive their server link
                }
                "full" -> full = true
                "signal" -> {
                    val plain = keys.unseal(m.optString("data")) ?: return
                    val d = try { JSONObject(plain) } catch (e: Exception) { return }
                    val from = d.optString("from")
                    if (from.isEmpty() || from == me) return
                    if (peerId != null && from != peerId) return
                    val s = d.optLong("seq", -1)
                    if (s <= peerSeq) return
                    peerId = from
                    peerSeq = s
                    onSignal(d)
                }
            }
        }

        fun sendSignal(obj: JSONObject) {
            obj.put("from", me).put("seq", ++seq)
            sendServer(JSONObject().put("type", "signal").put("room", id).put("data", keys.seal(obj.toString())))
        }

        fun sendDescription(d: SessionDescription) = sendSignal(
            JSONObject().put("description", JSONObject().put("type", d.type.canonicalForm()).put("sdp", d.description)),
        )

        fun createPeer(): PeerConnection {
            val config = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                iceTransportsType = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                // the same DTLS certificate every time, so the safety code stays the same
                this@Room.cert?.let { certificate = it }
            }
            val observer = Observer()
            val p = factory.createPeerConnection(config, observer)!!
            observer.self = p
            pc = p
            return p
        }

        inner class Observer : PeerConnection.Observer {
            var self: PeerConnection? = null

            private fun mine(block: (PeerConnection) -> Unit) = post {
                val p = self
                if (p != null && p === pc) block(p)
            }

            override fun onIceCandidate(c: IceCandidate) = mine {
                sendSignal(
                    JSONObject().put(
                        "candidate",
                        JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex),
                    ),
                )
            }

            override fun onRenegotiationNeeded() = mine { p ->
                if (p.signalingState() != PeerConnection.SignalingState.STABLE) return@mine
                try {
                    makingOffer = true
                    sdp { p.setLocalDescription(it) }
                    sendDescription(p.localDescription)
                } catch (e: Exception) {
                    Log.e(TAG, "offer failed", e)
                } finally {
                    makingOffer = false
                }
            }

            override fun onDataChannel(ch: DataChannel) = mine { setupDc(ch) }

            override fun onTrack(transceiver: RtpTransceiver) = mine {
                val track = transceiver.receiver.track()
                if (track is VideoTrack && call.room === this@Room) track.addSink(remoteSink)
            }

            override fun onSignalingChange(s: PeerConnection.SignalingState) {
                if (s == PeerConnection.SignalingState.STABLE) mine {
                    updateSafetyCode(it)
                    // Their camera can arrive on a transceiver added after the call was accepted,
                    // and onTrack doesn't reliably report it, so connect the screen again once each
                    // negotiation settles. Adding a sink twice is a no-op.
                    if (call.room === this@Room && call.state != CallState.IDLE) remoteVideos().forEach { v -> v.addSink(remoteSink) }
                }
            }

            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) = mine { p ->
                if (s == PeerConnection.PeerConnectionState.FAILED) p.restartIce()
                publish()
            }

            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        }

        fun onSignal(d: JSONObject) {
            val p = pc ?: createPeer()
            val desc = d.optJSONObject("description")
            if (desc != null) {
                val type = SessionDescription.Type.fromCanonicalForm(desc.getString("type"))
                val offer = type == SessionDescription.Type.OFFER
                val collision = offer && (makingOffer || p.signalingState() != PeerConnection.SignalingState.STABLE)
                ignoreOffer = !polite && collision
                if (ignoreOffer) return
                if (collision) sdp { p.setLocalDescription(it, SessionDescription(SessionDescription.Type.ROLLBACK, "")) }
                sdp { p.setRemoteDescription(it, SessionDescription(type, desc.getString("sdp"))) }
                pending.forEach { p.addIceCandidate(it) }
                pending.clear()
                if (offer) {
                    sdp { p.setLocalDescription(it) }
                    sendDescription(p.localDescription)
                }
                updateSafetyCode(p)
                return
            }
            val c = d.optJSONObject("candidate") ?: return
            val line = c.optString("candidate")
            if (line.isEmpty()) return
            val candidate = IceCandidate(if (c.isNull("sdpMid")) "0" else c.getString("sdpMid"), c.optInt("sdpMLineIndex"), line)
            if (p.remoteDescription == null) pending.add(candidate) else p.addIceCandidate(candidate)
        }

        fun teardown() {
            if (call.room === this) endCall(false)
            val ch = dc
            dc = null
            ch?.unregisterObserver()
            ch?.close()
            ch?.dispose()
            val p = pc
            pc = null
            p?.dispose()
            pending.clear()
            code = null
        }

        fun updateSafetyCode(p: PeerConnection) {
            val c = safetyCode(p.localDescription?.description, p.remoteDescription?.description) ?: return
            if (c == code) return
            code = c
            val verified = rec.verifiedCode
            if (verified != null && c != verified) {
                addSys("⚠ The safety code changed. This may be a different device or someone in the middle. Verify it again before saying anything sensitive.")
            }
            publish()
        }

        fun setupDc(ch: DataChannel) {
            dc = ch
            ch.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previous: Long) {}

                override fun onStateChange() = post {
                    if (dc !== ch) return@post
                    when (ch.state()) {
                        DataChannel.State.OPEN -> flushOutbox()
                        DataChannel.State.CLOSED -> if (call.room === this@Room) endCall(false)
                        else -> {}
                    }
                    publish()
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                    val text = String(bytes, Charsets.UTF_8)
                    post {
                        if (dc === ch) onPeerMessage(JSONObject(text))
                        publish()
                    }
                }
            })
        }

        // Pad to a multiple of PAD_BLOCK bytes, like the web app, so sizes don't reveal lengths.
        fun sendPeer(obj: JSONObject): Boolean {
            val ch = dc ?: return false
            if (ch.state() != DataChannel.State.OPEN) return false
            val len = obj.toString().toByteArray().size + 7 // + ,"p":""
            obj.put("p", ".".repeat((len + PAD_BLOCK - 1) / PAD_BLOCK * PAD_BLOCK - len))
            return ch.send(DataChannel.Buffer(ByteBuffer.wrap(obj.toString().toByteArray()), false))
        }

        fun msgJson(m: Msg) = JSONObject().put("t", "msg").put("id", m.id).put("text", m.text).put("ttl", m.ttl).put("ts", m.ts)

        fun flushOutbox() {
            var changed = false
            for (m in msgs) {
                // Resend anything not yet acknowledged: a message "sent" into a connection that had
                // silently died is never delivered otherwise. The receiver ignores duplicates by id.
                if (m.mine && m.status != "delivered" && sendPeer(msgJson(m)) && m.status == "pending") {
                    m.status = "sent"
                    changed = true
                }
            }
            if (changed) saveMsgs()
        }

        fun onPeerMessage(m: JSONObject) {
            when (m.optString("t")) {
                "msg" -> {
                    val mid = m.optString("id").ifEmpty { Crypto.newId() }.take(32)
                    sendPeer(JSONObject().put("t", "ack").put("id", mid))
                    if (msgs.any { it.id == mid && !it.mine }) return
                    val ttl = m.optInt("ttl").coerceIn(0, 86400)
                    val now = System.currentTimeMillis()
                    val msg = Msg(mid, false, false, m.optString("text").take(4000), now, ttl, if (ttl > 0) now + ttl * 1000L else 0, "")
                    msgs.add(msg)
                    scheduleExpiry(msg)
                    if (openRoomId != id || !foreground) rec.unread++
                    saveMsgs()
                    saveRooms()
                }
                "ack" -> msgs.firstOrNull { it.mine && it.id == m.optString("id") }?.let {
                    if (it.status != "delivered") { it.status = "delivered"; saveMsgs() }
                }
                "clear" -> {
                    msgs.clear()
                    addSys("The other person cleared the chat.")
                }
                "bye" -> {
                    val who = if (rec.role == "owner") "The participant" else "The other person"
                    rec.ended = true
                    saveRooms()
                    sendServer(JSONObject().put("type", "leave").put("room", id))
                    addSys("$who left this room. You can delete it from the ⋯ menu.")
                    teardown()
                }
                // Only the owner may end a room or remove the participant; a participant honours it.
                "end-room" -> if (rec.role != "owner") wipeRoom(id, notice = "The owner ended this room, so it was deleted here too.")
                "removed" -> if (rec.role != "owner") wipeRoom(id, notice = "The owner removed you from this room.")
                else -> onCallMessage(this, m)
            }
        }

        fun addSys(text: String) {
            msgs.add(Msg(Crypto.newId(), false, true, text, System.currentTimeMillis(), 0, 0, ""))
            saveMsgs()
            publish()
        }

        fun send(text: String, ttl: Int) {
            if (rec.ended) return
            val now = System.currentTimeMillis()
            val msg = Msg(Crypto.newId(), true, false, text.take(4000), now, ttl, if (ttl > 0) now + ttl * 1000L else 0, "pending")
            msgs.add(msg)
            scheduleExpiry(msg)
            if (sendPeer(msgJson(msg))) msg.status = "sent"
            saveMsgs()
            publish()
        }

        fun scheduleExpiry(m: Msg) {
            if (m.expires <= 0 || expiry.containsKey(m.id)) return
            expiry[m.id] = later((m.expires - System.currentTimeMillis()).coerceAtLeast(0)) {
                msgs.remove(m)
                expiry.remove(m.id)
                saveMsgs()
                publish()
            }
        }

        fun cancelTimers() = expiry.values.forEach { it?.cancel(false) }

        fun status(): Pair<String, String> = when {
            rec.ended -> "The other person left" to "bad"
            full -> "Open on another device" to "bad"
            dcOpen() -> ("Online · end-to-end encrypted" + if (code != null && code == rec.verifiedCode) " · verified" else "") to "ok"
            serverPeer -> "Connecting…" to ""
            !connected() -> "Reconnecting to the server…" to ""
            else -> "Offline · messages will be sent when they're back" to "off"
        }

        fun view(): RoomView {
            val (text, kind) = status()
            val last = msgs.lastOrNull { !it.sys }
            val preview = if (rec.ended) "The other person left" else last?.let { (if (it.mine) "You: " else "") + it.text } ?: "No messages yet"
            val views = msgs.map { it.view() }
            return RoomView(
                id, rec.name, text, kind, dcOpen(), rec.ended, rec.unread, preview,
                msgs.lastOrNull()?.ts ?: rec.created, code, code != null && code == rec.verifiedCode,
                rec.verifiedCode != null && code != null && code != rec.verifiedCode, rec.role == "owner", views,
                views.joinToString(",") { it.id + it.status },
            )
        }
    }

    private fun addRoom(secret: String, name: String, role: String): Room {
        val keys = RoomKeys(secret)
        rooms[keys.roomId]?.let { return it }
        val cert = try {
            RtcCertificatePem.generateCertificate(PeerConnection.KeyType.ECDSA, 10L * 365 * 24 * 3600).also {
                store.write("$CERT${keys.roomId}", JSONObject().put("key", it.privateKey).put("cert", it.certificate).toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "no saved certificate", e)
            null
        }
        val rec = RoomRec(keys.roomId, secret, name.ifBlank { "Room" }.take(40), System.currentTimeMillis(), null, 0, false, role)
        val room = Room(rec, cert)
        rooms[rec.id] = room
        room.addSys("Paired. Compare the safety code with the other person once: tap Check above.")
        saveRooms()
        room.join()
        return room
    }

    // Remove the room and everything it stores from this device.
    private fun wipeRoom(roomId: String, notice: String? = null) {
        val room = rooms[roomId] ?: return
        if (call.room === room) endCall(true)
        room.teardown()
        room.cancelTimers()
        rooms.remove(roomId)
        saveRooms()
        sendServer(JSONObject().put("type", "leave").put("room", roomId))
        store.delete("$MSGS$roomId")
        store.delete("$CERT$roomId")
        notice?.let { n -> main.post { if (!closed) ui.onNotice(n) } }
        publish()
    }

    // Participant leaves, or anyone deletes a finished room: gone here, the other side keeps an archive.
    private fun leaveNow(roomId: String) {
        val room = rooms[roomId] ?: return
        if (!room.rec.ended) room.sendPeer(JSONObject().put("t", "bye"))
        later(150) { wipeRoom(roomId) } // let the goodbye go out before the channel closes
    }

    // Owner: end the room for both people, history wiped on both sides.
    private fun endRoomForBothNow(roomId: String) {
        rooms[roomId]?.sendPeer(JSONObject().put("t", "end-room"))
        later(150) { wipeRoom(roomId) }
    }

    // Owner: remove the participant. They're kicked; the owner keeps the history as an archive.
    private fun removeParticipantNow(roomId: String) {
        val room = rooms[roomId] ?: return
        room.sendPeer(JSONObject().put("t", "removed"))
        later(150) {
            if (call.room === room) endCall(true)
            room.rec.ended = true
            room.addSys("You removed the participant. To talk again, start a new room.")
            saveRooms()
            sendServer(JSONObject().put("type", "leave").put("room", roomId))
            room.teardown()
            publish()
        }
    }

    // ---------- pairing: a 6-letter code -> a saved room ----------

    private val pairing = Pairer()

    private inner class Pairer {
        var step: String? = null // "create" | "join" | "name"
        var mode: String? = null
        var code: String? = null
        var keys: KeyPair? = null
        var secret: String? = null
        var expiresAt = 0L
        var status = ""
        var bad = false

        fun reset() {
            step = null; mode = null; code = null; keys = null; secret = null; status = ""; bad = false
        }

        fun say(text: String, isBad: Boolean = false) {
            status = text
            bad = isBad
            publish()
        }

        fun create() {
            reset()
            step = "create"
            mode = "create"
            if (!sendServer(JSONObject().put("type", "pair-create"))) {
                mode = null
                say("Not connected to the server yet. Try again in a moment.", true)
            } else {
                say("Getting a code…")
            }
        }

        fun join(text: String) {
            val c = Pairing.normalizeCode(text)
            step = "join"
            if (c.length != Pairing.CODE_LENGTH) return say("Enter all 6 letters of the code.", true)
            mode = "join"
            code = c
            if (!sendServer(JSONObject().put("type", "pair-join").put("code", c))) {
                mode = null
                say("Not connected to the server yet. Try again in a moment.", true)
            } else {
                say("Connecting…")
            }
        }

        fun cancel() {
            if (mode != null && secret == null) sendServer(JSONObject().put("type", "pair-end").put("reason", "cancelled"))
            reset()
            publish()
        }

        fun onDisconnect() {
            if (mode != null && secret == null) {
                mode = null
                say("Lost connection to the server. Please start again.", true)
            }
        }

        fun save(name: String): String? {
            val s = secret ?: return null
            val room = addRoom(s, name, if (mode == "create") "owner" else "participant")
            reset()
            publish()
            return room.id
        }

        fun onServer(m: JSONObject) {
            if (mode == null) return
            when (m.optString("type")) {
                "pair-created" -> {
                    code = m.optString("code")
                    expiresAt = System.currentTimeMillis() + m.optLong("expiresIn", 600_000)
                    say("Waiting for the other person to enter the code…")
                }
                "pair-peer" -> {
                    // Both sides send a fresh ECDH public key; the shared secret never crosses the network.
                    val kp = Pairing.newKeyPair()
                    keys = kp
                    val pub = Crypto.b64u(Pairing.rawPublic(kp.public as ECPublicKey))
                    sendServer(JSONObject().put("type", "pair-msg").put("data", JSONObject().put("pub", pub).toString()))
                    say("Connecting securely…")
                }
                "pair-msg" -> {
                    val kp = keys ?: return
                    if (secret != null) return
                    val pub = try { JSONObject(m.optString("data")).optString("pub") } catch (e: Exception) { "" }
                    if (pub.isEmpty()) return
                    try {
                        secret = Pairing.roomSecret(kp.private, kp.public as ECPublicKey, Crypto.unb64u(pub))
                    } catch (e: Exception) {
                        sendServer(JSONObject().put("type", "pair-end").put("reason", "failed"))
                        mode = null
                        return say("Pairing failed. Please get a new code.", true)
                    }
                    sendServer(JSONObject().put("type", "pair-end").put("reason", "done"))
                    step = "name"
                    say("")
                }
                "pair-ended" -> {
                    if (secret != null) return
                    val text = when (m.optString("reason")) {
                        "no-such-code" -> "No room is waiting for that code. Check the letters, or ask for a new code."
                        "too-many-attempts" -> "Too many attempts from this network. Wait 10 minutes and try again."
                        "expired" -> "This code expired. Get a new one."
                        "peer-gone" -> "The other person closed the pairing screen."
                        "cancelled" -> "The other person cancelled."
                        "failed" -> "Pairing failed. Please get a new code."
                        "busy" -> "The server is busy. Try again in a minute."
                        else -> "Pairing ended."
                    }
                    if (mode == "create") code = null
                    mode = null
                    say(text, true)
                }
            }
        }

        fun view() = PairView(step, code?.let { Pairing.formatCode(it) }?.takeIf { step == "create" }, expiresAt, status, bad)
    }

    // ---------- calls (one at a time, in any room) ----------

    private class Call {
        var room: Room? = null
        var state = CallState.IDLE
        var peerVideo = false
        var myVideo = false
    }

    private val call = Call()
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var camera: CameraVideoCapturer? = null
    private var cameraHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var frontCamera = true

    private fun onCallMessage(room: Room, m: JSONObject) {
        when (m.optString("t")) {
            "ring" -> {
                if (call.state != CallState.IDLE) {
                    room.sendPeer(JSONObject().put("t", "busy"))
                    return
                }
                call.room = room
                call.state = CallState.INCOMING
                call.peerVideo = m.optBoolean("video")
            }
            "accept" -> {
                if (call.room !== room || call.state != CallState.OUTGOING) return
                call.peerVideo = m.optBoolean("video")
                call.state = CallState.ACTIVE
                room.remoteVideos().forEach { it.addSink(remoteSink) }
                attachTracks(room)
            }
            "decline", "busy" -> {
                if (call.room !== room || call.state != CallState.OUTGOING) return
                endCall(false)
                room.addSys(if (m.optString("t") == "busy") "The other person is busy." else "Call declined.")
            }
            "cancel", "end" -> {
                if (call.room !== room || call.state == CallState.IDLE) return
                if (call.state == CallState.INCOMING) room.addSys("Missed call.")
                endCall(false)
            }
            "video" -> if (call.room === room) call.peerVideo = m.optBoolean("on")
        }
        publish()
    }

    private fun ensureMic() {
        if (audioTrack != null) return
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("audio0", audioSource)
    }

    // Reuse existing transceivers so repeated calls don't grow the connection (like the web app).
    private fun attachTracks(room: Room) {
        val p = room.pc ?: return
        for (track in listOfNotNull(audioTrack, videoTrack)) {
            if (p.senders.any { it.track() === track }) continue
            val type = if (track is VideoTrack) MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO else MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
            val free = p.transceivers.firstOrNull { it.mediaType == type && it.sender.track() == null && !it.isStopped }
            if (free != null) {
                free.sender.setTrack(track, false)
                free.sender.setStreams(listOf("stream0"))
                if (free.direction != RtpTransceiver.RtpTransceiverDirection.SEND_RECV) {
                    free.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                }
            } else {
                p.addTrack(track, listOf("stream0"))
            }
        }
    }

    // The camera only runs while it's on; turning it off releases it (and its light).
    private fun startCamera(): Boolean {
        if (videoTrack != null) return true
        return try {
            val cams = Camera2Enumerator(appContext)
            val name = cams.deviceNames.firstOrNull { cams.isFrontFacing(it) == frontCamera }
                ?: cams.deviceNames.firstOrNull() ?: return false
            frontCamera = cams.isFrontFacing(name)
            val cap = cams.createCapturer(name, null) ?: return false
            val helper = SurfaceTextureHelper.create("camera", egl.eglBaseContext)
            val source = factory.createVideoSource(false)
            cap.initialize(helper, appContext, source.capturerObserver)
            cap.startCapture(1280, 720, 30)
            val track = factory.createVideoTrack("video0", source)
            track.addSink(localSink)
            camera = cap
            cameraHelper = helper
            videoSource = source
            videoTrack = track
            call.myVideo = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "camera failed", e)
            stopCamera()
            false
        }
    }

    private fun stopCamera() {
        val track = videoTrack
        if (track != null) call.room?.pc?.senders?.forEach { if (it.track() === track) it.setTrack(null, false) }
        track?.removeSink(localSink)
        try {
            camera?.stopCapture()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        camera?.dispose()
        track?.dispose()
        videoSource?.dispose()
        cameraHelper?.dispose()
        camera = null
        cameraHelper = null
        videoSource = null
        videoTrack = null
        call.myVideo = false
    }

    private fun startCallNow(roomId: String, wantVideo: Boolean) {
        val room = rooms[roomId] ?: return
        if (call.state != CallState.IDLE || !room.dcOpen()) return
        ensureMic()
        val video = wantVideo && startCamera()
        if (wantVideo && !video) room.addSys("Could not start the camera, calling with voice only.")
        main.post { audio.start() }
        call.room = room
        call.state = CallState.OUTGOING
        call.peerVideo = false
        room.sendPeer(JSONObject().put("t", "ring").put("video", video))
        publish()
    }

    private fun acceptCallNow(wantVideo: Boolean) {
        val room = call.room ?: return
        if (call.state != CallState.INCOMING) return
        ensureMic()
        val video = wantVideo && startCamera()
        if (wantVideo && !video) room.addSys("Could not start the camera, answering with voice only.")
        main.post { audio.start() }
        call.state = CallState.ACTIVE
        room.remoteVideos().forEach { it.addSink(remoteSink) }
        attachTracks(room)
        room.sendPeer(JSONObject().put("t", "accept").put("video", video))
        publish()
    }

    private fun setCameraNow(on: Boolean) {
        val room = call.room ?: return
        if (call.state != CallState.OUTGOING && call.state != CallState.ACTIVE) return
        if (on) {
            if (!startCamera()) return room.addSys("Could not start the camera.")
            if (call.state == CallState.ACTIVE) attachTracks(room)
        } else {
            stopCamera()
        }
        room.sendPeer(JSONObject().put("t", "video").put("on", on))
        publish()
    }

    private fun endCall(notify: Boolean) {
        val room = call.room
        if (notify && room != null && call.state != CallState.IDLE) {
            room.sendPeer(JSONObject().put("t", if (call.state == CallState.OUTGOING) "cancel" else "end"))
        }
        stopCamera()
        room?.pc?.senders?.forEach { if (it.track() != null) it.setTrack(null, false) }
        room?.remoteVideos()?.forEach { it.removeSink(remoteSink) }
        audioTrack?.dispose()
        audioSource?.dispose()
        audioTrack = null
        audioSource = null
        if (call.state != CallState.IDLE) main.post { audio.stop() }
        call.room = null
        call.state = CallState.IDLE
        call.peerVideo = false
        publish()
    }

    // ---------- snapshot for the screen ----------

    private fun view() = HubView(
        connected(),
        rooms.values.map { it.view() }.sortedByDescending { it.lastTs },
        pairing.view(),
        CallView(call.room?.id, call.state, call.peerVideo, call.myVideo, frontCamera),
    )

    companion object {
        private const val TAG = "Notes" // shows in logcat, so nothing that names the app
        // file names in the app's storage, kept neutral for the same reason
        private const val ROOMS = "index"
        private const val MSGS = "n-"
        private const val CERT = "c-"
        private const val PAD_BLOCK = 256

        @Volatile private var initialized = false

        fun init(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
            )
            initialized = true
        }
    }
}
```


## `android/app/src/main/java/app/plainnotes/Lock.kt`

<a id="androidappsrcmainjavaappplainnoteslockkt"></a>

```kotlin
package app.plainnotes

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// The Notes disguise. When it's on, the app opens as a plain notes list; adding a note that is the
// secret phrase asks for the PIN, and only the right PIN shows Ghostline.
//
// The PIN also guards the data: rooms and history are encrypted with a random data key, kept only
// wrapped by a key derived from the phrase and PIN. Without both, the files can't be read, even by
// this app. The phrase has its own quick salted hash so a note can be recognised as it's added;
// the PIN is checked by whether it unwraps the data key, so no hash of it is stored.
class Lock(context: Context) {
    private val appContext = context.applicationContext
    private val device = Store(appContext) // the disguise's own settings use the Keystore key
    @Volatile private var cfg: JSONObject? = try {
        device.read(FILE)?.let { JSONObject(it) }
    } catch (e: Exception) {
        Log.e("Notes", "could not read the disguise settings", e)
        null
    }

    // the unwrapped data key, only while unlocked
    @Volatile var dataKey: SecretKey? = null
        private set

    val enabled get() = cfg != null

    fun isPhrase(text: String) = cfg?.let { c ->
        val salt = Crypto.unb64u(c.getString("salt"))
        MessageDigest.isEqual(pbkdf2("phrase:${normalize(text)}", salt, PHRASE_ROUNDS), Crypto.unb64u(c.getString("phrase")))
    } ?: false

    // Slow (key derivation and maybe re-encryption): call off the main thread. false for a wrong PIN.
    fun unlock(phrase: String, pin: String): Boolean {
        val c = cfg ?: return false
        if (!c.has("wrap")) {
            // set up before the PIN protected the data: check the old hash, then move the data over
            val salt = Crypto.unb64u(c.getString("salt"))
            if (!MessageDigest.isEqual(pbkdf2("pin:$pin", salt, PHRASE_ROUNDS), Crypto.unb64u(c.getString("pin")))) return false
            set(phrase, pin)
            return true
        }
        val key = unwrap(Crypto.unb64u(c.getString("wrap")), phrase, pin, Crypto.unb64u(c.getString("kdfSalt"))) ?: return false
        dataKey = key
        // finishes a switch that was interrupted, leaving files on the Keystore key
        Store(appContext, key).rekey(device)
        return true
    }

    // Turns the disguise on, or changes the phrase and PIN while unlocked. Slow: call off the main
    // thread, with nothing else writing to the store. Changing the PIN only re-wraps the data key.
    fun set(phrase: String, pin: String) {
        val key = dataKey ?: SecretKeySpec(Crypto.randomBytes(32), "AES")
        val salt = Crypto.randomBytes(16)
        val kdfSalt = Crypto.randomBytes(16)
        val c = JSONObject()
            .put("salt", Crypto.b64u(salt))
            .put("phrase", Crypto.b64u(pbkdf2("phrase:${normalize(phrase)}", salt, PHRASE_ROUNDS)))
            .put("kdfSalt", Crypto.b64u(kdfSalt))
            .put("wrap", Crypto.b64u(wrap(key, phrase, pin, kdfSalt)))
        // settings first: if this is interrupted, the next unlock finishes moving the files
        device.write(FILE, c.toString())
        cfg = c
        dataKey = key
        Store(appContext, key).rekey(device)
    }

    // Turns the disguise off, moving the data back to the Keystore key. Same rules as set().
    fun disable() {
        dataKey?.let { device.rekey(Store(appContext, it)) }
        device.delete(FILE)
        cfg = null
        dataKey = null
    }

    fun relock() {
        dataKey = null
    }

    companion object {
        const val FILE = "prefs" // a neutral name, like everything in storage
        private const val PHRASE_ROUNDS = 20_000 // checked on every note added, so kept quick
        private const val KEY_ROUNDS = 200_000 // once per unlock

        // so the keyboard's auto-capitals or a stray space don't stop the phrase working
        fun normalize(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

        private fun pbkdf2(value: String, salt: ByteArray, rounds: Int): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(value.toCharArray(), salt, rounds, 256))
                .encoded

        private fun wrapKey(phrase: String, pin: String, salt: ByteArray) =
            SecretKeySpec(pbkdf2("key:${normalize(phrase)}:$pin", salt, KEY_ROUNDS), "AES")

        fun wrap(key: SecretKey, phrase: String, pin: String, salt: ByteArray): ByteArray {
            val iv = Crypto.randomBytes(12)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, wrapKey(phrase, pin, salt), GCMParameterSpec(128, iv))
            return iv + c.doFinal(key.encoded)
        }

        // null for the wrong phrase or PIN
        fun unwrap(wrapped: ByteArray, phrase: String, pin: String, salt: ByteArray): SecretKey? = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, wrapKey(phrase, pin, salt), GCMParameterSpec(128, wrapped, 0, 12))
            SecretKeySpec(c.doFinal(wrapped, 12, wrapped.size - 12), "AES")
        } catch (e: Exception) {
            null
        }
    }
}
```


## `android/app/src/main/java/app/plainnotes/MainActivity.kt`

<a id="androidappsrcmainjavaappplainnotesmainactivitykt"></a>

```kotlin
package app.plainnotes

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.util.TypedValue
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

class MainActivity : Activity(), HubUi {
    private enum class Screen { NOTES, PIN, HOME, PAIR, ROOM, DISGUISE }

    private lateinit var lock: Lock
    private var locked = false // showing the Notes disguise; Ghostline isn't connected
    private var disguiseEditing = false
    private var focusNotes = false
    private var pendingPhrase: String? = null // the phrase note that led to the PIN screen
    private var working = false // re-encrypting the rooms; the hub is stopped meanwhile
    private var started = false
    private var hub: Hub? = null
    private lateinit var audio: CallAudio
    private lateinit var egl: EglBase
    private val main = Handler(Looper.getMainLooper())
    private var view: HubView? = null
    private var screen = Screen.HOME
    private var roomId: String? = null
    private var pairStep = "create"
    private var callMinimized = false
    private var muted = false
    private var fillVideo = true
    private var lastCallState = CallState.IDLE
    private var builtKey: String? = null // what the current HOME / PAIR screen was built from

    // persistent layers
    private lateinit var content: FrameLayout
    private lateinit var callScreen: FrameLayout
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var callTitle: TextView
    private lateinit var muteBtn: ImageButton
    private lateinit var speakerBtn: ImageButton
    private lateinit var camBtn: ImageButton
    private lateinit var flipBtn: ImageButton
    private lateinit var incoming: LinearLayout
    private lateinit var incomingText: TextView
    private lateinit var callBar: Button

    // room screen
    private var messages: LinearLayout? = null
    private var messagesScroll: ScrollView? = null
    private var renderedMsgs: String? = null
    private var roomRefs: RoomRefs? = null
    private var ttlIndex = 0
    private var expiryText: TextView? = null

    private class RoomRefs(
        val title: TextView, val status: TextView, val dot: View, val avatar: TextView,
        val voice: ImageButton, val video: ImageButton, val banner: LinearLayout, val bannerText: TextView,
        val input: EditText, val send: ImageButton, val timer: ImageButton,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screenshots, screen recording and the app-switcher thumbnail.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Hub.init(this)
        audio = CallAudio(this)
        egl = EglBase.create()
        lock = Lock(this)
        buildLayers()
        if (lock.enabled) {
            locked = true
            show(Screen.NOTES)
        } else {
            startHub()
            show(Screen.HOME)
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        hub?.setForeground(true)
    }

    override fun onStop() {
        started = false
        hub?.setForeground(false)
        // With the disguise on, leaving the app locks it again, unless a call is going on. While
        // re-encrypting it waits, and locks once that's done.
        val call = view?.call
        if (lock.enabled && !locked && !working && (call == null || !inCall(call))) relock()
        else if (screen == Screen.PIN) show(Screen.NOTES)
        super.onStop()
    }

    private fun unlock() {
        hideKeyboard()
        locked = false
        pendingPhrase = null
        startHub()
        show(Screen.HOME)
    }

    private fun relock() {
        locked = true
        pendingPhrase = null
        lock.relock()
        hub?.close()
        hub = null
        view = null
        lastCallState = CallState.IDLE
        callMinimized = false
        incoming.visibility = View.GONE
        callBar.visibility = View.GONE
        callScreen.visibility = View.GONE
        setCallFullScreen(false)
        show(Screen.NOTES)
    }

    override fun onDestroy() {
        hub?.close()
        hub = null
        remoteView.release()
        localView.release()
        egl.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val call = view?.call
        when {
            call != null && inCall(call) && !callMinimized -> {
                callMinimized = true
                render()
            }
            screen == Screen.ROOM || screen == Screen.DISGUISE -> show(Screen.HOME)
            screen == Screen.PIN -> show(Screen.NOTES)
            screen == Screen.PAIR -> {
                hub?.pairCancel()
                show(Screen.HOME)
            }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun server() = getSharedPreferences("settings", MODE_PRIVATE).getString("server", "") ?: ""

    private fun startHub() {
        hub?.close()
        hub = null
        val origin = server()
        if (origin.isEmpty()) return
        hub = Hub(this, origin, this, audio, egl, remoteView, localView, lock.dataKey).also { it.start() }
    }

    override fun onView(view: HubView) {
        if (locked) return // a late update from the hub that relock() just closed
        this.view = view
        val call = view.call
        if (call.state == CallState.INCOMING && lastCallState != CallState.INCOMING) vibrate()
        if (call.state == CallState.IDLE && lastCallState != CallState.IDLE) {
            muted = false
            callMinimized = false
        }
        lastCallState = call.state
        if (screen == Screen.ROOM && view.rooms.none { it.id == roomId }) show(Screen.HOME) else render()
    }

    override fun onNotice(text: String) {
        if (locked) return
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Room closed").setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun inCall(c: CallView) = c.state == CallState.OUTGOING || c.state == CallState.ACTIVE

    // ---------- navigation ----------

    private fun show(s: Screen, room: String? = null) {
        screen = s
        roomId = if (s == Screen.ROOM) room else null
        hub?.setOpenRoom(roomId)
        builtKey = null
        renderedMsgs = null
        roomRefs = null
        messages = null
        content.removeAllViews()
        setBars(light = s == Screen.NOTES || s == Screen.PIN)
        keepScreenOn()
        if (s == Screen.ROOM) content.addView(buildRoom())
        render()
    }

    private fun render() {
        if (working) {
            rebuildIfChanged("working") {
                column(pad = 32).apply {
                    gravity = Gravity.CENTER
                    setBackgroundColor(BG)
                    addView(text("Encrypting your rooms…", 16f, MUTED).apply { gravity = Gravity.CENTER })
                }
            }
            return
        }
        when (screen) {
            Screen.NOTES -> rebuildIfChanged("notes|" + notes()) { buildNotes() }
            Screen.PIN -> rebuildIfChanged("pin") { buildPin() }
            Screen.DISGUISE -> rebuildIfChanged("disguise|${lock.enabled}|$disguiseEditing") { buildDisguise() }
            Screen.HOME -> rebuildIfChanged(homeKey()) { buildHome() }
            Screen.PAIR -> rebuildIfChanged(pairKey()) { buildPair() }
            Screen.ROOM -> renderRoom()
        }
        renderCall()
    }

    // Rebuild only when what the screen shows changed, so typing in a field isn't interrupted.
    private fun rebuildIfChanged(key: String, build: () -> View) {
        if (key == builtKey) return
        builtKey = key
        content.removeAllViews()
        content.addView(build())
    }

    private fun homeKey(): String {
        val v = view
        return "${server()}|${hub != null}|${lock.enabled}|${v?.connected}|" + v?.rooms?.joinToString(";") {
            "${it.id},${it.name},${it.preview},${it.unread},${it.ready},${it.ended}"
        }
    }

    private fun pairKey(): String {
        val p = view?.pair
        return "$pairStep|${p?.step}|${p?.code}|${p?.status}|${p?.bad}|${p?.expiresAt}"
    }

    // ---------- home ----------

    private fun buildHome(): View {
        val v = view
        val root = column(pad = 0)
        val top = row().apply { setPadding(dp(20), dp(20), dp(12), dp(8)) }
        top.addView(text("Ghostline", 30f, TEXT, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (hub != null && v != null && !v.connected) top.addView(text("Connecting…", 13f, WAIT))
        root.addView(top)

        if (server().isEmpty()) {
            val box = column(pad = 20)
            box.addView(text("Enter your Ghostline server to start.", 15f, MUTED).spaced(0, 8))
            val field = field("https://your-server.example.com", "")
            field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            box.addView(field)
            box.addView(button("Save", primary = true) { saveServer(field.text.toString()) }.spaced(12, 0))
            root.addView(box)
            return scroll(root)
        }

        val actions = row().apply { setPadding(dp(17), dp(8), dp(17), dp(8)) }
        actions.addView(button("New room", primary = true) {
            pairStep = "create"
            show(Screen.PAIR)
            hub?.pairCreate()
        }, weighted())
        actions.addView(button("Join with code") {
            pairStep = "join"
            show(Screen.PAIR)
        }, weighted())
        root.addView(actions)

        val rooms = v?.rooms.orEmpty()
        if (rooms.isEmpty()) {
            root.addView(text("No rooms yet.\nTap New room to get a code, or Join with code if someone gave you one.", 15f, MUTED).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.2f)
                setPadding(dp(32), dp(48), dp(32), dp(48))
            })
        } else {
            root.addView(text("ROOMS", 12f, MUTED, bold = true).apply { letterSpacing = 0.08f; setPadding(dp(20), dp(16), dp(20), dp(4)) })
            for (r in rooms) root.addView(roomRow(r))
        }

        root.addView(
            text(
                "No app can protect you from spyware already on your phone that records the screen, keyboard or microphone. Use a phone you trust.",
                12f, Color.parseColor("#cdb67a"),
            ).apply {
                background = rounded(Color.parseColor("#1f1a0e"), 12)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setLineSpacing(0f, 1.15f)
            }.spaced(24, 0, 20),
        )
        root.addView(text("Disguise as Notes · ${if (lock.enabled) "On" else "Off"}", 12f, MUTED).apply {
            setPadding(dp(20), dp(14), dp(20), 0)
            background = ripple()
            setOnClickListener {
                disguiseEditing = false
                show(Screen.DISGUISE)
            }
        })
        root.addView(text("Server · ${server().removePrefix("https://")}", 12f, MUTED).apply {
            setPadding(dp(20), dp(14), dp(20), dp(24))
            background = ripple()
            setOnClickListener { askServer() }
        })
        return scroll(root)
    }

    // ---------- notes (the disguise) ----------

    private fun notes(): JSONArray = try {
        JSONArray(getSharedPreferences("notes", MODE_PRIVATE).getString("items", "[]"))
    } catch (e: Exception) {
        JSONArray()
    }

    private fun saveNotes(a: JSONArray) {
        getSharedPreferences("notes", MODE_PRIVATE).edit().putString("items", a.toString()).apply()
        render()
    }

    private fun buildNotes(): View {
        val items = notes()
        val root = column(pad = 0)
        val top = column(pad = 0).apply { setPadding(dp(20), dp(24), dp(20), dp(12)) }
        top.addView(text("Notes", 30f, N_TEXT, bold = true))
        top.addView(text(DateFormat.getDateInstance(DateFormat.FULL).format(Date()), 13f, N_MUTED).apply { setPadding(0, dp(2), 0, 0) })
        root.addView(top)

        val composer = row().apply { setPadding(dp(16), dp(4), dp(16), dp(12)) }
        val input = edit("Add a note", "").apply {
            setTextColor(N_TEXT)
            background = rounded(Color.WHITE, 12, N_LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // the secret phrase is typed here, so the keyboard mustn't learn it or suggest it later
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(200))
        }
        input.setOnEditorActionListener { _, _, e -> if (e?.action != KeyEvent.ACTION_UP) addNote(input); true }
        composer.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        composer.addView(iconButton(R.drawable.ic_add, "Add note", tint = Color.WHITE, bg = N_ACCENT, size = 46) { addNote(input) })
        root.addView(composer)

        if (items.length() == 0) {
            root.addView(text("No notes yet.", 15f, N_MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(dp(32), dp(48), dp(32), dp(48))
            })
        }
        for (i in 0 until items.length()) root.addView(noteRow(items, i))

        if (focusNotes) {
            focusNotes = false
            focusLater(input)
        }
        return ScrollView(this).apply {
            setBackgroundColor(N_BG)
            isFillViewport = true
            addView(root)
        }
    }

    private fun noteRow(items: JSONArray, i: Int): View {
        val note = items.getJSONObject(i)
        val done = note.optBoolean("done")
        val item = row().apply {
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = ripple(bounded = true)
            setOnClickListener {
                note.put("done", !done)
                saveNotes(items)
            }
            setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setMessage("Delete this note?")
                    .setPositiveButton("Delete") { _, _ -> items.remove(i); saveNotes(items) }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }
        item.addView(View(this).apply {
            background = if (done) circle(N_ACCENT) else circle(Color.TRANSPARENT).apply { setStroke(dp(2), N_MUTED) }
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(14) })
        item.addView(text(note.optString("t"), 16f, if (done) N_MUTED else N_TEXT).apply {
            if (done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return item
    }

    private fun addNote(input: EditText) {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        if (lock.isPhrase(t)) {
            pendingPhrase = t
            show(Screen.PIN)
            return
        }
        val items = notes()
        items.put(JSONObject().put("t", t).put("done", false))
        focusNotes = true
        saveNotes(items)
    }

    // Looks like a notes app's locked note. A wrong PIN just goes back to the notes.
    private fun buildPin(): View {
        val root = column(pad = 24).apply {
            setBackgroundColor(N_BG)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(96), dp(32), dp(32))
        }
        root.addView(text("Locked note", 22f, N_TEXT, bold = true).apply { gravity = Gravity.CENTER })
        root.addView(text("Enter the PIN to view this note.", 14f, N_MUTED).apply { gravity = Gravity.CENTER }.spaced(6, 0))
        val field = edit("PIN", "").apply {
            setTextColor(N_TEXT)
            textSize = 26f
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(12))
            background = rounded(Color.WHITE, 12, N_LINE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        lateinit var openButton: Button
        // Unwrapping the data key takes a moment, so it runs off the main thread.
        val open: () -> Unit = open@{
            if (!field.isEnabled) return@open
            val phrase = pendingPhrase.orEmpty()
            val pin = field.text.toString()
            field.isEnabled = false
            openButton.isEnabled = false
            openButton.text = "Opening…"
            thread {
                val ok = try {
                    lock.unlock(phrase, pin)
                } catch (e: Exception) {
                    Log.e("Notes", "unlock failed", e)
                    false
                }
                runOnUiThread {
                    if (ok && screen == Screen.PIN && started) {
                        unlock()
                    } else {
                        lock.relock() // also if they left while it was opening
                        if (screen == Screen.PIN) {
                            hideKeyboard()
                            show(Screen.NOTES)
                        }
                    }
                }
            }
        }
        field.setOnEditorActionListener { _, _, e -> if (e?.action != KeyEvent.ACTION_UP) open(); true }
        root.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(28) })
        openButton = button("Open", primary = true) { open() }.apply { background = rounded(N_ACCENT, 14) }
        root.addView(openButton.spaced(14, 0))
        root.addView(text("Cancel", 15f, N_MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { hideKeyboard(); show(Screen.NOTES) }
        }.spaced(4, 0))
        focusLater(field)
        return root
    }

    // Focus a field once it's on screen; asking before it's attached is ignored.
    private fun focusLater(field: EditText) = field.post {
        field.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(field, 0)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    // ---------- disguise settings ----------

    private fun buildDisguise(): View {
        val root = column(pad = 0)
        val top = row().apply { setPadding(dp(4), dp(8), dp(16), dp(8)) }
        top.addView(iconButton(R.drawable.ic_back, "Back") { show(Screen.HOME) })
        top.addView(text("Disguise as Notes", 20f, TEXT, bold = true))
        root.addView(top)
        val body = column(pad = 20)
        root.addView(body)

        if (lock.enabled && !disguiseEditing) {
            body.addView(text(
                "On. Ghostline opens as a Notes app. To get in, add a note that is your secret phrase, then enter your PIN.\n\n" +
                    "Your rooms are encrypted with a key that only your phrase and PIN can unlock. " +
                    "It locks again whenever you leave the app, except during a call. While locked you are offline, " +
                    "so messages arrive when you next open it.",
                15f, MUTED,
            ).apply { setLineSpacing(0f, 1.15f) })
            body.addView(button("Change phrase and PIN") { disguiseEditing = true; render() }.spaced(20, 0))
            body.addView(button("Turn off", danger = true) {
                confirm(
                    "Turn off the disguise?",
                    "Ghostline will open normally again, and your rooms will no longer need the PIN to be read.",
                    "Turn off",
                ) {
                    rekeying({ lock.disable() }) { show(Screen.DISGUISE) }
                }
            }.spaced(10, 0))
            return scroll(root)
        }

        body.addView(text(
            "Ghostline will open as a Notes app. Typing your secret phrase as a new note asks for your PIN, and the right PIN opens Ghostline.\n\n" +
                "Your rooms will be encrypted with a key that only your phrase and PIN unlock. Pick a phrase you would never write as a real note. " +
                "If you forget either one, nobody can recover your rooms; the only way back in is to clear the app's data, which deletes them.",
            15f, MUTED,
        ).apply { setLineSpacing(0f, 1.15f) })
        val phrase = field("Secret phrase", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(200))
        }
        body.addView(phrase, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) })
        val pin = field("PIN (4–12 digits)", "").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(12))
        }
        body.addView(pin, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        val again = field("PIN again", "").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(12))
        }
        body.addView(again, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        body.addView(button(if (lock.enabled) "Save" else "Turn on", primary = true) {
            val p = pin.text.toString()
            when {
                Lock.normalize(phrase.text.toString()).length < 4 -> toast("The phrase needs at least 4 characters.")
                !Regex("^\\d{4,12}$").matches(p) -> toast("The PIN needs 4 to 12 digits.")
                p != again.text.toString() -> toast("The PINs don't match.")
                else -> {
                    val ph = phrase.text.toString()
                    val changing = lock.enabled
                    rekeying({ lock.set(ph, p) }) {
                        toast(if (changing) "Phrase and PIN changed." else "Disguise on. It takes effect when you leave the app.")
                        show(Screen.HOME)
                    }
                }
            }
        }.spaced(16, 0))
        return scroll(root)
    }

    // Re-encrypting must not race the hub's own saves, so the hub is stopped while it runs.
    private fun rekeying(work: () -> Unit, done: () -> Unit) {
        val call = view?.call
        if (call != null && call.state != CallState.IDLE) {
            toast("Finish the call first.")
            return
        }
        hideKeyboard()
        val h = hub
        hub = null
        view = null
        h?.close()
        working = true
        render()
        thread {
            h?.awaitClosed()
            val ok = try {
                work()
                true
            } catch (e: Exception) {
                Log.e("Notes", "re-encrypting failed", e)
                false
            }
            runOnUiThread {
                working = false
                if (!ok) toast("Couldn't finish. Please try again.")
                if (!started && lock.enabled) relock() else {
                    startHub()
                    done()
                }
            }
        }
    }

    // Light bars for the Notes disguise, dark ones for Ghostline.
    @Suppress("DEPRECATION")
    private fun setBars(light: Boolean) {
        window.statusBarColor = if (light) N_BG else BG
        window.navigationBarColor = if (light) N_BG else SURFACE
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
        } else {
            window.decorView.systemUiVisibility =
                if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        }
    }

    private fun roomRow(r: RoomView): View {
        val item = row().apply {
            setPadding(dp(20), dp(12), dp(16), dp(12))
            background = ripple(bounded = true)
            setOnClickListener { show(Screen.ROOM, r.id) }
        }
        val av = FrameLayout(this)
        av.addView(avatar(r.name, 48), FrameLayout.LayoutParams(dp(48), dp(48)))
        av.addView(View(this).apply {
            background = circle(dotColor(if (r.ended) "bad" else if (r.ready) "ok" else "off")).apply { setStroke(dp(2), BG) }
        }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.BOTTOM or Gravity.END))
        item.addView(av, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(14) })
        val body = column(pad = 0)
        body.addView(text(r.name, 16f, TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        body.addView(text(r.preview, 14f, if (r.unread > 0) TEXT else MUTED).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })
        item.addView(body, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val side = column(pad = 0).apply { gravity = Gravity.END }
        side.addView(text(shortTime(r.lastTs), 12f, if (r.unread > 0) ACCENT else MUTED))
        if (r.unread > 0) {
            side.addView(text(if (r.unread > 99) "99+" else r.unread.toString(), 12f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                minWidth = dp(22)
                background = rounded(ACCENT, 11)
                setPadding(dp(7), dp(2), dp(7), dp(2))
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4); gravity = Gravity.END })
        }
        item.addView(side, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
        return item
    }

    private fun saveServer(input: String) {
        val t = input.trim().trimEnd('/')
        val m = Regex("^(https?)://([A-Za-z0-9.-]+)(:\\d+)?$").find(t)
        if (m == null || (m.groupValues[1] == "http" && m.groupValues[2] !in setOf("localhost", "127.0.0.1"))) {
            toast("Enter the server address, starting with https://")
            return
        }
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("server", t).apply()
        startHub()
        show(Screen.HOME)
    }

    private fun askServer() {
        val field = edit("https://…", server())
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Server")
            .setMessage("Both people must use the same server. Your rooms stay on this phone.")
            .setView(FrameLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(field) })
            .setPositiveButton("Save") { _, _ -> saveServer(field.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- pairing ----------

    private fun buildPair(): View {
        val p = view?.pair
        val step = p?.step ?: pairStep
        val root = column(pad = 0)
        val top = row().apply { setPadding(dp(4), dp(8), dp(16), dp(8)) }
        top.addView(iconButton(R.drawable.ic_back, "Back") { hub?.pairCancel(); show(Screen.HOME) })
        top.addView(text(when (step) { "join" -> "Join with code"; "name" -> "Room ready"; else -> "New room" }, 20f, TEXT, bold = true))
        root.addView(top)
        val body = column(pad = 20)
        root.addView(body)
        expiryText = null

        when (step) {
            "create" -> {
                body.addView(text("Share this code with one person. It works once and expires in 10 minutes.", 15f, MUTED).apply { setLineSpacing(0f, 1.15f) })
                body.addView(text(p?.code ?: "· · ·", 44f, TEXT, bold = true).apply {
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.CENTER
                    letterSpacing = 0.12f
                    background = rounded(SURFACE, 18, BORDER)
                    setPadding(0, dp(28), 0, dp(28))
                    setTextIsSelectable(true)
                }.spaced(24, 10))
                expiryText = text("", 13f, MUTED).apply { gravity = Gravity.CENTER }
                body.addView(expiryText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                updateExpiry()
            }
            "join" -> {
                body.addView(text("Enter the 6-letter code shown on the other person's screen.", 15f, MUTED))
                val field = field("ABC-DEF", "").apply {
                    textSize = 30f
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.CENTER
                    letterSpacing = 0.12f
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    filters = arrayOf(InputFilter.LengthFilter(7), InputFilter.AllCaps())
                    imeOptions = EditorInfo.IME_ACTION_GO
                    setPadding(dp(16), dp(18), dp(16), dp(18))
                    setOnEditorActionListener { tv, _, _ -> hub?.pairJoin(tv.text.toString()); true }
                }
                body.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) })
                body.addView(button("Join", primary = true) { hub?.pairJoin(field.text.toString()) }.spaced(14, 0))
                field.requestFocus()
            }
            "name" -> {
                val ok = row()
                ok.addView(ImageView(this).apply { setImageResource(R.drawable.ic_shield); imageTintList = ColorStateList.valueOf(OK) },
                    LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(10) })
                ok.addView(text("Paired", 20f, OK, bold = true))
                body.addView(ok)
                body.addView(text("Give this room a name. Only you see it.", 15f, MUTED).spaced(12, 0))
                val field = field("e.g. Alex", "").apply { filters = arrayOf(InputFilter.LengthFilter(40)) }
                body.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
                body.addView(button("Save", primary = true) {
                    hub?.pairSave(field.text.toString()) { id -> show(Screen.ROOM, id) }
                }.spaced(14, 0))
                field.requestFocus()
            }
        }
        val status = p?.status.orEmpty()
        if (status.isNotEmpty()) body.addView(text(status, 14f, if (p?.bad == true) DANGER else MUTED).apply { gravity = Gravity.CENTER }.spaced(18, 0))
        return scroll(root)
    }

    // The countdown updates only its own text, so the screen isn't rebuilt every second.
    private fun updateExpiry() {
        val t = expiryText ?: return
        val p = view?.pair
        if (p?.code == null || p.expiresAt <= 0) { t.text = ""; return }
        val left = ((p.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        t.text = "Expires in ${left / 60}:${"%02d".format(left % 60)}"
        main.removeCallbacks(tick)
        if (left > 0) main.postDelayed(tick, 1000)
    }

    private val tick = Runnable { if (screen == Screen.PAIR) updateExpiry() }

    // ---------- room ----------

    private fun buildRoom(): View {
        val root = column(pad = 0)
        val bar = row().apply { setBackgroundColor(SURFACE); setPadding(dp(2), dp(6), dp(4), dp(6)) }
        bar.addView(iconButton(R.drawable.ic_back, "Back") { show(Screen.HOME) })
        val av = avatar("", 38)
        bar.addView(av, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(10) })
        val titleBox = column(pad = 0)
        val title = text("", 16f, TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        titleBox.addView(title)
        val statusRow = row()
        val dot = View(this)
        statusRow.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(5) })
        val status = text("", 12f, MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        statusRow.addView(status)
        titleBox.addView(statusRow)
        bar.addView(titleBox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val voice = iconButton(R.drawable.ic_call, "Voice call") { roomId?.let { id -> withPermissions(video = false) { hub?.startCall(id, false) } } }
        val video = iconButton(R.drawable.ic_video, "Video call") { roomId?.let { id -> withPermissions(video = true) { cam -> hub?.startCall(id, cam) } } }
        bar.addView(voice)
        bar.addView(video)
        bar.addView(iconButton(R.drawable.ic_more, "Room options") { anchor -> roomMenu(anchor) })
        root.addView(bar)

        // until the safety code is verified, a slim banner offers to check it
        val banner = row().apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
            setOnClickListener { currentRoom()?.let { safetyDialog(it) } }
        }
        banner.addView(ImageView(this).apply { setImageResource(R.drawable.ic_shield); imageTintList = ColorStateList.valueOf(WAIT) },
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) })
        val bannerText = text("", 13f, TEXT)
        banner.addView(bannerText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        banner.addView(text("Check", 13f, ACCENT, bold = true))
        root.addView(banner)

        val list = column(pad = 0).apply { setPadding(dp(12), dp(8), dp(12), dp(12)) }
        val scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        messages = list
        messagesScroll = scroll

        val composer = row().apply { setPadding(dp(6), dp(6), dp(8), dp(10)); gravity = Gravity.BOTTOM }
        val timer = iconButton(R.drawable.ic_timer, "Disappearing messages") { anchor -> ttlMenu(anchor) }
        composer.addView(timer)
        val input = field("Message", "").apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(4000))
            maxLines = 5
            background = rounded(SURFACE2, 22)
            setPadding(dp(16), dp(11), dp(16), dp(11))
        }
        composer.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(2); rightMargin = dp(8) })
        val send = iconButton(R.drawable.ic_send, "Send", tint = Color.WHITE, bg = ACCENT, size = 46) { sendMessage() }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false }
        composer.addView(send)
        root.addView(composer)

        roomRefs = RoomRefs(title, status, dot, av, voice, video, banner, bannerText, input, send, timer)
        return root.apply { setBackgroundColor(BG) }
    }

    private fun ttlMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, -1, 0, "Disappearing messages").isEnabled = false
            TTL_LABELS.forEachIndexed { i, label -> menu.add(0, i, i + 1, (if (i == ttlIndex) "✓ " else "    ") + label) }
            setOnMenuItemClickListener { item ->
                if (item.itemId >= 0) {
                    ttlIndex = item.itemId
                    renderRoom()
                }
                true
            }
            show()
        }
    }

    private fun safetyDialog(r: RoomView) {
        val msg = when {
            r.code == null -> "The safety code appears once you're connected."
            r.codeChanged -> "The safety code changed since you verified it. This may be a different device or someone in the middle.\n\n${r.code}\n\nCompare it with the other person again before saying anything sensitive."
            r.verified -> "${r.code}\n\nVerified. You'll be warned if it ever changes."
            else -> "${r.code}\n\nRead it out loud on a call, or compare it in person. If both screens show the same numbers, nobody is in the middle."
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Safety code").setMessage(msg).apply {
            if (r.code != null && !r.verified) setPositiveButton("They match") { _, _ -> hub?.verify(r.id) }
            setNegativeButton("Close", null)
        }.show()
    }

    private fun currentRoom() = view?.rooms?.firstOrNull { it.id == roomId }

    private fun sendMessage() {
        val refs = roomRefs ?: return
        val id = roomId ?: return
        val text = refs.input.text.toString().trim()
        if (text.isEmpty() || currentRoom()?.ended == true) return
        hub?.send(id, text, TTL_SECONDS[ttlIndex])
        refs.input.setText("")
    }

    private fun renderRoom() {
        val r = currentRoom() ?: return
        val refs = roomRefs ?: return
        val call = view?.call
        refs.title.text = r.name
        refs.status.text = r.status
        refs.dot.background = circle(dotColor(r.kind))
        styleAvatar(refs.avatar, r.name)
        val idle = call == null || call.state == CallState.IDLE
        for (b in listOf(refs.voice, refs.video)) {
            b.isEnabled = r.ready && idle
            b.alpha = if (b.isEnabled) 1f else 0.35f
        }
        refs.input.isEnabled = !r.ended
        refs.send.isEnabled = !r.ended
        refs.send.alpha = if (r.ended) 0.35f else 1f
        val ttl = TTL_SECONDS[ttlIndex]
        refs.timer.imageTintList = ColorStateList.valueOf(if (ttl > 0) ACCENT else MUTED)
        refs.input.hint = when {
            r.ended -> "This room has ended"
            ttl > 0 -> "Message · disappears after ${TTL_LABELS[ttlIndex]}"
            r.ready -> "Message"
            else -> "Message · sent when they're online"
        }
        when {
            r.codeChanged -> {
                refs.banner.visibility = View.VISIBLE
                refs.banner.setBackgroundColor(Color.parseColor("#3a1717"))
                refs.bannerText.text = "Safety code changed. Check it before saying anything sensitive."
            }
            r.code != null && !r.verified && !r.ended -> {
                refs.banner.visibility = View.VISIBLE
                refs.banner.setBackgroundColor(Color.parseColor("#1f1a0e"))
                refs.bannerText.text = "Compare the safety code to make sure this chat is private."
            }
            else -> refs.banner.visibility = View.GONE
        }

        val key = r.id + ":" + r.msgsKey
        if (key == renderedMsgs) return
        renderedMsgs = key
        val list = messages ?: return
        list.removeAllViews()
        var prevDay: String? = null
        var prevMine: Boolean? = null
        for (m in r.msgs) {
            val day = dayText(m.ts)
            if (day != prevDay) {
                list.addView(pill(day))
                prevDay = day
                prevMine = null
            }
            list.addView(messageView(m, sameSender = !m.sys && prevMine == m.mine))
            prevMine = if (m.sys) null else m.mine
        }
        messagesScroll?.post { messagesScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun pill(s: String) = text(s, 12f, MUTED).apply {
        gravity = Gravity.CENTER
        background = rounded(SURFACE, 10)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12); bottomMargin = dp(4) }
    }

    private fun messageView(m: MsgView, sameSender: Boolean): View {
        if (m.sys) {
            return text(m.text, 12f, MUTED).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.15f)
                setPadding(dp(24), dp(8), dp(24), dp(8))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
        }
        val r = dp(18).toFloat()
        val small = dp(5).toFloat()
        val bubble = column(pad = 0).apply {
            background = GradientDrawable().apply {
                setColor(if (m.mine) ME else THEM)
                // the corner on the sender's side is tucked in, like most chat apps
                cornerRadii = if (m.mine) floatArrayOf(r, r, r, r, small, small, r, r) else floatArrayOf(r, r, r, r, r, r, small, small)
            }
            setPadding(dp(13), dp(8), dp(13), dp(6))
            // explicit WRAP_CONTENT: a vertical LinearLayout's default MATCH_PARENT would squeeze it to the time's width
            addView(text(m.text, 15.5f, TEXT).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.1f) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            val tick = if (m.mine) when (m.status) { "pending" -> "  🕓"; "sent" -> "  ✓"; "delivered" -> "  ✓✓"; else -> "" } else ""
            val ttl = if (m.ttl > 0) "⏱ " else ""
            addView(text(ttl + shortClock(m.ts) + tick, 11f, if (m.mine) Color.parseColor("#b9c8ea") else MUTED).apply {
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.END; topMargin = dp(2) })
        }
        bubble.layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(if (sameSender) 3 else 10)
            gravity = if (m.mine) Gravity.END else Gravity.START
            if (m.mine) leftMargin = dp(56) else rightMargin = dp(56)
        }
        return bubble
    }

    private fun shortClock(ts: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))

    private fun dayText(ts: Long): String {
        val f = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val d = f.format(Date(ts))
        return when (d) {
            f.format(Date()) -> "Today"
            f.format(Date(System.currentTimeMillis() - 86_400_000)) -> "Yesterday"
            else -> d
        }
    }

    private fun shortTime(ts: Long) = if (dayText(ts) == "Today") shortClock(ts) else dayText(ts)

    private fun roomMenu(anchor: View) {
        val r = currentRoom() ?: return
        PopupMenu(this, anchor).apply {
            menu.add(0, 0, 0, if (r.owner) "You own this room" else "You joined this room").isEnabled = false
            menu.add(0, 1, 1, "Rename")
            menu.add(0, 2, 2, "Clear chat")
            if (r.owner && !r.ended) menu.add(0, 4, 3, "Remove participant")
            menu.add(0, 3, 4, if (r.ended) "Delete room" else if (r.owner) "End room for both" else "Leave room")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> rename(r)
                    2 -> confirm(
                        "Clear chat?",
                        if (r.ready) "Messages are deleted on both devices." else "The other person is offline, so only your copy is deleted.",
                        "Clear",
                    ) { hub?.clearChat(r.id) }
                    3 -> when {
                        r.ended -> confirm("Delete this room?", "Its chat history is deleted from this phone.", "Delete") { hub?.leave(r.id) }
                        r.owner -> confirm(
                            "End this room for both?",
                            "The room and its chat history are deleted on both devices. " +
                                (if (r.ready) "" else "The other person is offline, so this reaches them when they next connect. ") +
                                "This cannot be undone.",
                            "End for both",
                        ) { hub?.endRoom(r.id) }
                        else -> confirm(
                            "Leave this room?",
                            "The room and its chat history are deleted from this phone. " +
                                (if (r.ready) "The owner is told you left. " else "The owner is offline, so they'll just see you as offline. ") +
                                "To talk again you'll need a new code.",
                            "Leave",
                        ) { hub?.leave(r.id) }
                    }
                    4 -> confirm(
                        "Remove the participant?",
                        "They are removed and their copy is deleted. " +
                            (if (r.ready) "" else "They are offline, so this takes effect when they next connect. ") +
                            "You keep the chat history here as a read-only archive.",
                        "Remove",
                    ) { hub?.removeParticipant(r.id) }
                }
                true
            }
            show()
        }
    }

    private fun rename(r: RoomView) {
        val field = edit("Name", r.name).apply { filters = arrayOf(InputFilter.LengthFilter(40)) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Rename room")
            .setMessage("Only you see this name.")
            .setView(FrameLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(field) })
            .setPositiveButton("Save") { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let { hub?.rename(r.id, it) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirm(title: String, message: String, ok: String, action: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(ok) { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- calls ----------

    private fun buildLayers() {
        content = FrameLayout(this)

        callScreen = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#1b2433"), Color.BLACK))
            visibility = View.GONE
            isClickable = true
        }
        remoteView = SurfaceViewRenderer(this).apply {
            init(egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            visibility = View.GONE
            // tap to switch between filling the screen and showing the whole picture
            setOnClickListener {
                fillVideo = !fillVideo
                setScalingType(if (fillVideo) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            }
        }
        callScreen.addView(remoteView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        localView = SurfaceViewRenderer(this).apply {
            init(egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setZOrderMediaOverlay(true) // the self-view draws above the other person's video
            setMirror(true)
            visibility = View.GONE
        }
        callScreen.addView(localView, cornerParams())

        // name and state; centred with a big avatar when there's no video
        callAvatar = avatar("", 112).apply { textSize = 44f }
        callScreen.addView(callAvatar, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER).apply { bottomMargin = dp(120) })
        val top = row().apply { setPadding(dp(20), dp(48), dp(12), dp(12)) }
        callTitle = text("", 20f, Color.WHITE, bold = true).apply { setShadowLayer(8f, 0f, 1f, Color.BLACK); setLineSpacing(0f, 1.1f) }
        top.addView(callTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(iconButton(R.drawable.ic_chat, "Back to chat", tint = Color.WHITE, bg = Color.argb(70, 255, 255, 255)) { callMinimized = true; render() })
        callScreen.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        val controls = row().apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(40), dp(8), dp(40))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(210, 0, 0, 0), Color.TRANSPARENT))
        }
        muteBtn = callControl(controls, R.drawable.ic_mic, "Mute") { muted = !muted; hub?.setMuted(muted); renderCall() }
        speakerBtn = callControl(controls, R.drawable.ic_speaker, "Speaker") { audio.toggleSpeaker(); renderCall() }
        camBtn = callControl(controls, R.drawable.ic_video, "Camera") {
            if (view?.call?.myVideo == true) hub?.setCamera(false) else withPermissions(video = true) { cam -> if (cam) hub?.setCamera(true) }
        }
        flipBtn = callControl(controls, R.drawable.ic_flip, "Flip") { hub?.flipCamera() }
        callControl(controls, R.drawable.ic_call_end, "End", bg = DANGER) { hub?.hangUp() }
        callScreen.addView(controls, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        incoming = column(pad = 16).apply {
            visibility = View.GONE
            background = rounded(SURFACE2, 20, BORDER)
            elevation = dp(10).toFloat()
        }
        val ihead = row()
        incomingAvatar = avatar("", 44)
        ihead.addView(incomingAvatar, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        incomingText = text("Incoming call", 16f, TEXT, bold = true)
        ihead.addView(incomingText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        incoming.addView(ihead)
        val ir = row().apply { gravity = Gravity.END }.spaced(14, 0)
        ir.addView(iconButton(R.drawable.ic_call_end, "Decline", tint = Color.WHITE, bg = DANGER, size = 52) { hub?.declineCall() },
            LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(14) })
        ir.addView(iconButton(R.drawable.ic_video, "Answer with video", tint = Color.WHITE, bg = OK, size = 52) {
            withPermissions(video = true, onDenied = { hub?.declineCall() }) { cam -> hub?.acceptCall(cam); openCallRoom() }
        }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(14) })
        ir.addView(iconButton(R.drawable.ic_call, "Answer", tint = Color.WHITE, bg = OK, size = 52) {
            withPermissions(video = false, onDenied = { hub?.declineCall() }) { hub?.acceptCall(false); openCallRoom() }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        incoming.addView(ir)

        callBar = Button(this).apply {
            text = "Call in progress · tap to return"
            isAllCaps = false
            setTextColor(Color.parseColor("#04210f"))
            setBackgroundColor(OK)
            visibility = View.GONE
            setOnClickListener { openCallRoom() }
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(BG)
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(callScreen, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(incoming, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply { setMargins(dp(10), dp(40), dp(10), 0) })
            addView(callBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })
    }

    // A round call button with a small label underneath.
    private fun callControl(parent: LinearLayout, icon: Int, label: String, bg: Int = Color.argb(60, 255, 255, 255), onClick: () -> Unit): ImageButton {
        val box = column(pad = 0).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val b = iconButton(icon, label, tint = Color.WHITE, bg = bg, size = 56) { onClick() }
        box.addView(b, LinearLayout.LayoutParams(dp(56), dp(56)))
        box.addView(text(label, 12f, Color.WHITE).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        parent.addView(box, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return b
    }

    private lateinit var callAvatar: TextView
    private lateinit var incomingAvatar: TextView

    private fun openCallRoom() {
        val id = view?.call?.roomId ?: return
        callMinimized = false
        if (screen != Screen.ROOM || roomId != id) show(Screen.ROOM, id) else render()
    }

    private fun renderCall() {
        val c = view?.call ?: return
        val name = view?.rooms?.firstOrNull { it.id == c.roomId }?.name ?: ""
        val active = inCall(c)
        incoming.visibility = if (c.state == CallState.INCOMING) View.VISIBLE else View.GONE
        incomingText.text = "$name\nIncoming ${if (c.peerVideo) "video" else "voice"} call"
        styleAvatar(incomingAvatar, name)
        val full = active && !callMinimized
        callScreen.visibility = if (full) View.VISIBLE else View.GONE
        callBar.visibility = if (active && callMinimized) View.VISIBLE else View.GONE
        val remote = c.state == CallState.ACTIVE && c.peerVideo
        remoteView.visibility = if (remote && full) View.VISIBLE else View.GONE
        localView.visibility = if (c.myVideo && full) View.VISIBLE else View.GONE
        localView.setMirror(c.frontCamera)
        // until the other person's video arrives, your own camera fills the screen
        localView.layoutParams = if (remote) cornerParams() else FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        callAvatar.visibility = if (remote || c.myVideo) View.GONE else View.VISIBLE
        styleAvatar(callAvatar, name)
        callTitle.text = "$name\n" + when {
            c.state == CallState.OUTGOING -> "Calling…"
            remote || c.myVideo -> "Video call · end-to-end encrypted"
            else -> "Voice call · end-to-end encrypted"
        }
        if (!active) muted = false
        setToggle(muteBtn, muted)
        setToggle(speakerBtn, audio.speaker)
        setToggle(camBtn, c.myVideo)
        flipBtn.isEnabled = c.myVideo
        flipBtn.alpha = if (c.myVideo) 1f else 0.35f
        setCallFullScreen(full)
        keepScreenOn()
    }

    // During a call, and while pairing: the screen timing out would lock the app (with the disguise
    // on) and so break off the pairing.
    private fun keepScreenOn() {
        val c = view?.call
        if (screen == Screen.PAIR || (c != null && c.state != CallState.IDLE)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // "on" buttons are white with a dark icon, like most call screens
    private fun setToggle(b: ImageButton, on: Boolean) {
        b.background = circle(if (on) Color.WHITE else Color.argb(60, 255, 255, 255))
        b.imageTintList = ColorStateList.valueOf(if (on) Color.BLACK else Color.WHITE)
    }

    private var immersive = false

    // Full screen during calls: draw under the hidden system bars and the camera cutout.
    @Suppress("DEPRECATION")
    private fun setCallFullScreen(on: Boolean) {
        if (on == immersive) return
        immersive = on
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (on) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(!on)
            window.insetsController?.let { c ->
                if (on) {
                    c.hide(WindowInsets.Type.systemBars())
                    c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    c.show(WindowInsets.Type.systemBars())
                }
            }
        } else {
            window.decorView.systemUiVisibility = if (on) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            } else {
                0
            }
        }
    }

    private fun cornerParams() = FrameLayout.LayoutParams(dp(110), dp(150), Gravity.TOP or Gravity.END).apply {
        setMargins(dp(12), dp(110), dp(12), dp(12))
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 300, 400, 300, 400), -1))
    }

    // ---------- permissions ----------

    // Ask for the microphone (and the camera for video). The action gets whether the camera
    // may be used, so a video call falls back to voice if only the camera is refused.
    private fun withPermissions(video: Boolean, onDenied: (() -> Unit)? = null, action: (camera: Boolean) -> Unit) {
        val wanted = listOfNotNull(Manifest.permission.RECORD_AUDIO, if (video) Manifest.permission.CAMERA else null)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return action(video)
        pendingAction = action
        pendingDenied = onDenied
        pendingVideo = video
        requestPermissions(missing.toTypedArray(), 1)
    }

    private var pendingAction: ((Boolean) -> Unit)? = null
    private var pendingDenied: (() -> Unit)? = null
    private var pendingVideo = false

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val granted = { p: String -> checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED }
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            val camera = pendingVideo && granted(Manifest.permission.CAMERA)
            if (pendingVideo && !camera) toast("Camera permission was refused, so this call is voice only.")
            pendingAction?.invoke(camera)
        } else {
            toast("Microphone permission is needed for calls.")
            pendingDenied?.invoke()
        }
        pendingAction = null
        pendingDenied = null
    }

    // ---------- tiny view helpers ----------

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dotColor(kind: String) = when (kind) {
        "ok" -> OK
        "bad" -> DANGER
        "off" -> Color.parseColor("#4a525e")
        else -> WAIT
    }

    private fun scroll(v: View) = ScrollView(this).apply { setBackgroundColor(BG); addView(v) }

    private fun ripple(bounded: Boolean = false) = TypedValue().let { tv ->
        theme.resolveAttribute(if (bounded) android.R.attr.selectableItemBackground else android.R.attr.selectableItemBackgroundBorderless, tv, true)
        getDrawable(tv.resourceId)
    }

    private fun iconButton(icon: Int, desc: String, tint: Int = TEXT, bg: Int? = null, size: Int = 44, onClick: (View) -> Unit) =
        ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = desc
            imageTintList = ColorStateList.valueOf(tint)
            background = if (bg != null) circle(bg) else ripple()
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
            setOnClickListener { onClick(it) }
        }

    // A coloured circle with the first letter of the name; the colour is stable per name.
    private fun avatar(name: String, size: Int) = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = size / 2.6f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        styleAvatar(this, name)
    }

    private fun styleAvatar(v: TextView, name: String) {
        v.text = name.trim().take(1).uppercase().ifEmpty { "·" }
        v.background = circle(AVATAR_COLORS[(name.hashCode() and 0x7fffffff) % AVATAR_COLORS.size])
    }

    // An input field with a rounded background instead of an underline.
    private fun field(hint: String, value: String) = edit(hint, value).apply {
        background = rounded(SURFACE2, 12, BORDER)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        textSize = 16f
    }

    private fun column(pad: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun edit(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        textSize = 15f
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun button(label: String, primary: Boolean = false, danger: Boolean = false, onClick: (View) -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(if (danger) DANGER else Color.WHITE)
            background = rounded(if (primary) ACCENT else SURFACE2, 14, if (danger) DANGER else null)
            minHeight = dp(50)
            textSize = 15f
            if (primary) setTypeface(typeface, Typeface.BOLD)
            stateListAnimator = null
            setOnClickListener { onClick(it) }
        }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun weighted() = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }

    private fun <T : View> T.spaced(top: Int, bottom: Int, side: Int = 0): T = apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
            leftMargin = dp(side)
            rightMargin = dp(side)
        }
    }

    companion object {
        private val BG = Color.parseColor("#0d0f12")
        private val SURFACE = Color.parseColor("#161a20")
        private val SURFACE2 = Color.parseColor("#1f252d")
        private val TEXT = Color.parseColor("#e7eaee")
        private val MUTED = Color.parseColor("#8b95a3")
        private val ACCENT = Color.parseColor("#4f8cff")
        private val OK = Color.parseColor("#3ccf8e")
        private val WAIT = Color.parseColor("#f2b84b")
        private val DANGER = Color.parseColor("#ef5b5b")
        private val ME = Color.parseColor("#2f5bb7")
        private val THEM = Color.parseColor("#232a33")
        private val BORDER = Color.parseColor("#2a313b")
        // the Notes disguise is light, so it doesn't look like Ghostline
        private val N_BG = Color.parseColor("#faf8f3")
        private val N_TEXT = Color.parseColor("#22252a")
        private val N_MUTED = Color.parseColor("#8d9096")
        private val N_ACCENT = Color.parseColor("#e3a21a")
        private val N_LINE = Color.parseColor("#e7e3d9")
        private val AVATAR_COLORS = listOf("#5b7cfa", "#22a39f", "#d9822b", "#c2508f", "#7e57c2", "#3f9e4d", "#c9534f").map { Color.parseColor(it) }
        private val TTL_LABELS = listOf("Keep", "30s", "5m", "1h")
        private val TTL_SECONDS = listOf(0, 30, 300, 3600)
    }
}
```


## `android/app/src/main/java/app/plainnotes/Store.kt`

<a id="androidappsrcmainjavaappplainnotesstorekt"></a>

```kotlin
package app.plainnotes

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Rooms and chat history, saved as files in the app's private storage, each encrypted with
// AES-GCM. The key is either one that lives in the Android Keystore and can't be exported, or,
// with the Notes disguise on, a data key that only exists once the phrase and PIN unwrap it.
class Store(context: Context, dataKey: SecretKey? = null) {
    private val dir = File(context.filesDir, "data").apply { mkdirs() }
    private val key: SecretKey = dataKey ?: loadKey()

    private fun loadKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    fun read(name: String): String? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return open(f.readBytes()) ?: run {
            Log.e("Notes", "could not read $name")
            null
        }
    }

    // null if this key didn't encrypt it
    private fun open(bytes: ByteArray): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
        String(c.doFinal(bytes, 12, bytes.size - 12))
    } catch (e: Exception) {
        null
    }

    fun write(name: String, text: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        // the Keystore picks a fresh random IV itself and refuses one from us
        if (key is SecretKeySpec) c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, Crypto.randomBytes(12)))
        else c.init(Cipher.ENCRYPT_MODE, key)
        val out = c.iv + c.doFinal(text.toByteArray())
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(out)
        tmp.renameTo(File(dir, name)) // atomic replace
    }

    fun delete(name: String) {
        File(dir, name).delete()
    }

    // Re-encrypts with this store's key every file that `from` can open. Files already on this key
    // are left alone, so after an interruption it can simply be run again. The disguise's own
    // settings stay on the Keystore key, since they're needed before anything is unlocked.
    fun rekey(from: Store) {
        for (f in dir.listFiles().orEmpty()) {
            if (f.name == Lock.FILE || f.name.endsWith(".tmp")) continue
            val bytes = f.readBytes()
            if (open(bytes) != null) continue
            from.open(bytes)?.let { write(f.name, it) }
        }
    }

    companion object {
        private const val ALIAS = "notes-store"
    }
}
```


## `android/app/src/main/res/drawable/ic_add.xml`

<a id="androidappsrcmainresdrawableic_addxml"></a>

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/>
</vector>
```


## `android/app/src/main/res/drawable/ic_back.xml`

<a id="androidappsrcmainresdrawableic_backxml"></a>

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"/>
</vector>
```


## `android/app/src/main/res/drawable/ic_call.xml`

<a id="androidappsrcmainresdrawableic_callxml"></a>

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 1.12,0.37 2.33,0.57 3.57,0.57 0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 -9.39,0 -17,-7.61 -17,-17 0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 0,1.25 0.2,2.45 0.57,3.57 0.11,0.35 0.03,0.74 -0.25,1.02l-2.2,2.2z"/>
</vector>
```


## `android/app/src/main/res/drawable/ic_call_end.xml`

<a id="androidappsrcmainresdrawableic_call_endxml"></a>

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M12,9c-1.6,0 -3.15,0.25 -4.6,0.72v3.1c0,0.39 -0.23,0.74 -0.56,0.9 -0.98,0.49 -1.87,1.12 -2.66,1.85 -0.18,0.18 -0.43,0.28 -0.7,0.28 -0.28,0 -0.53,-0.11 -0.71,-0.29L0.29,13.08c-0.18,-0.17 -0.29,-0.42 -0.29,-0.7 0,-0.28 0.11,-0.53 0.29,-0.71C3.34,8.78 7.46,7 12,7s8.66,1.78 11.71,4.67c0.18,0.18 0.29,0.43 0.29,0.71 0,0.28 -0.11,0.53 -0.29,0.71l-2.48,2.48c-0.18,0.18 -0.43,0.29 -0.71,0.29 -0.27,0 -0.52,-0.11 -0.7,-0.28 -0.79,-0.74 -1.69,-1.36 -2.67,-1.85 -0.33,-0.16 -0.56,-0.5 -0.56,-0.9v-3.1C15.15,9.25 13.6,9 12,9z"/>
</vector>
```


## `android/app/src/main/res/drawable/ic_chat.xml`

<a id="androidappsrcmainresdrawableic_chatxml"></a>

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M20,2H4c-1.1,0 -1.99,0.9 -1.99,2L2,22l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z"/>
</vector>
```


## `android/app/src/main/res/drawable/ic_flip.xml`

<a id="androidappsrcmainresdrawableic_flipxml"></a>

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M16,7h-1l-1,-1h-4L9,7H8c-1.1,0 -2,0.9 -2,2v6c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V9c0,-1.1 -0.9,-2 -2,-2zM12,15c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3z"/>
</vector>
```


## `android/app/src/main/res/drawable/ic_keypad.xml`

<a id="androidappsrcmainresdrawableic_keypadxml"></a>

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M20,5H4c-1.1,0 -1.99,0.9 -1.99,2L2,17c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM11,8h2v2h-2V8zM11,11h2v2h-2v-2zM8,8h2v2H8V8zM8,11h2v2H8v-2zM7,13H5v-2h2v2zM7,10H5V8h2v2zM16,17H8v-2h8v2zM16,13h-2v-2h2v2zM16,10h-2V8h2v2zM19,13h-2v-2h2v2zM19,10h-2V8h2v2z"/>
</vector>
```


## `android/app/src/main/res/drawable/ic_launcher_background.xml`

<a id="androidappsrcmainresdrawableic_launcher_backgroundxml"></a>

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#F2B533"
        android:pathData="M0,0h108v108h-108z" />
</vector>
```


## `android/app/src/main/res/drawable/ic_launcher_foreground.xml`

<a id="androidappsrcmainresdrawableic_launcher_foregroundxml"></a>

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- the sheet, inside the 66dp safe zone -->
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M40,30h28a4,4 0,0 1,4 4v40a4,4 0,0 1,-4 4h-28a4,4 0,0 1,-4 -4v-40a4,4 0,0 1,4 -4z" />
    <path
        android:fillColor="#C9CCD1"
        android:pathData="M43,42h22v3h-22z M43,51h22v3h-22z M43,60h22v3h-22z M43,69h13v3h-13z" />
</vector>
```


## `android/app/src/main/res/drawable/ic_mic.xml`

<a id="androidappsrcmainresdrawableic_micxml"></a>

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"/>
</vector>
```


## `android/app/src/main/res/drawable/ic_more.xml`

<a id="androidappsrcmainresdrawableic_morexml"></a>

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z"/>
</vector>
```


## `android/app/src/main/res/drawable/ic_send.xml`

<a id="androidappsrcmainresdrawableic_sendxml"></a>

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z"/>
</vector>
```


## `android/app/src/main/res/drawable/ic_shield.xml`

<a id="androidappsrcmainresdrawableic_shieldxml"></a>

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4zM10,17l-4,-4 1.41,-1.41L10,14.17l6.59,-6.59L18,9l-8,8z"/>
</vector>
```


## `android/app/src/main/res/drawable/ic_speaker.xml`

<a id="androidappsrcmainresdrawableic_speakerxml"></a>

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"/>
</vector>
```


## `android/app/src/main/res/drawable/ic_timer.xml`

<a id="androidappsrcmainresdrawableic_timerxml"></a>

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M15,1H9v2h6V1zM11,14h2V8h-2v6zM19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z"/>
</vector>
```


## `android/app/src/main/res/drawable/ic_video.xml`

<a id="androidappsrcmainresdrawableic_videoxml"></a>

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M17,10.5V7c0,-0.55 -0.45,-1 -1,-1H4c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h12c0.55,0 1,-0.45 1,-1v-3.5l4,4v-11l-4,4z"/>
</vector>
```


## `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`

<a id="androidappsrcmainresmipmap-anydpi-v26ic_launcherxml"></a>

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- A plain notepad, to match the Notes disguise. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```


## `android/app/src/main/res/xml/network_security_config.xml`

<a id="androidappsrcmainresxmlnetwork_security_configxml"></a>

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- HTTPS only, except a local server reached over USB (adb reverse) for testing. -->
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">localhost</domain>
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
</network-security-config>
```


## `android/app/src/test/java/app/plainnotes/CryptoTest.kt`

<a id="androidappsrctestjavaappplainnotescryptotestkt"></a>

```kotlin
package app.plainnotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Vectors produced by the web app's code path (WebCrypto in Node), so the Android app
// is proven to derive the same room and decrypt what browsers send.
class CryptoTest {
    private val secret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val webRoomId = "4da9120812de3682bfa7e188a412d7d3caeeeba2774d0f1c9630fc6e6fdc1d1e"
    private val webSealed = "j1CN3FPFfSaLvT_5.fNaMy9eMb06vcTH2O1_7KwkIsj66ks6EgaSsdaxRQPQ0NCa8-S2inSF1"

    @Test fun derivesSameRoomIdAsWeb() = assertEquals(webRoomId, RoomKeys(secret).roomId)

    @Test fun decryptsWhatWebEncrypted() =
        assertEquals("{\"hello\":\"wörld\",\"seq\":1}", RoomKeys(secret).unseal(webSealed))

    @Test fun roundTrips() {
        val k = RoomKeys(secret)
        assertEquals("ping", k.unseal(k.seal("ping")))
    }

    @Test fun rejectsTamperedOrForeign() {
        assertNull(RoomKeys(secret).unseal(webSealed.dropLast(2) + "AA"))
        assertNull(RoomKeys(Crypto.b64u(ByteArray(32))).unseal(webSealed))
    }

    @Test fun safetyCodeMatchesWeb() = assertEquals(
        "8994 0998 1377 9789",
        safetyCode("v=0\r\na=fingerprint:sha-256 AA:BB:CC\r\n", "a=fingerprint:sha-256 11:22:33\r\n"),
    )

    // Keys and expected secret produced by the web app's pairing code path (WebCrypto).
    @Test fun pairingSecretMatchesWeb() {
        val priv = java.security.KeyFactory.getInstance("EC")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode("MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgB5PTdZ7yRlI6T6GNvRxS+5igBELI3umC4HK0dcVtaHahRANCAASATIZ+wazmXKnZLIbM0yF5qoLIa4LqlxnEiq4+9km9vdKYDl6pjuX8p2oTrEYVVnmWHNUdqy09sxCMPgrkvNC9")))
        val aRaw = Crypto.unb64u("BIBMhn7BrOZcqdkshszTIXmqgshrguqXGcSKrj72Sb290pgOXqmO5fynahOsRhVWeZYc1R2rLT2zEIw-CuS80L0")
        val params = (Pairing.newKeyPair().public as java.security.interfaces.ECPublicKey).params
        val aPub = java.security.KeyFactory.getInstance("EC").generatePublic(
            java.security.spec.ECPublicKeySpec(
                java.security.spec.ECPoint(java.math.BigInteger(1, aRaw.copyOfRange(1, 33)), java.math.BigInteger(1, aRaw.copyOfRange(33, 65))),
                params,
            ),
        ) as java.security.interfaces.ECPublicKey
        assertEquals("BIBMhn7BrOZcqdkshszTIXmqgshrguqXGcSKrj72Sb290pgOXqmO5fynahOsRhVWeZYc1R2rLT2zEIw-CuS80L0", Crypto.b64u(Pairing.rawPublic(aPub)))
        assertEquals("ZrpW9-P23rJY2xyFnsl9gzedxmgJUexhmPWbKat0UkU", Pairing.roomSecret(priv, aPub, Crypto.unb64u("BBIpxgNL2AjJj9nxccN_M_iPrbUAgyCnmlpj0-kGyprbFoLmjjdduCgbdv9saEp9XED5RhOCDzYsuLMNsDPkQ9c")))
    }

    @Test fun pairingBothSidesAgree() {
        val a = Pairing.newKeyPair()
        val b = Pairing.newKeyPair()
        val pa = a.public as java.security.interfaces.ECPublicKey
        val pb = b.public as java.security.interfaces.ECPublicKey
        assertEquals(
            Pairing.roomSecret(a.private, pa, Pairing.rawPublic(pb)),
            Pairing.roomSecret(b.private, pb, Pairing.rawPublic(pa)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun pairingRejectsPointOffCurve() {
        val a = Pairing.newKeyPair()
        val bad = ByteArray(65).also { it[0] = 4; it[64] = 1 }
        Pairing.roomSecret(a.private, a.public as java.security.interfaces.ECPublicKey, bad)
    }

    @Test fun codes() {
        assertEquals("KQDMWP", Pairing.normalizeCode("kqd-mwp "))
        assertEquals("KQD-MWP", Pairing.formatCode("KQDMWP"))
    }
}
```


## `android/app/src/test/java/app/plainnotes/LockTest.kt`

<a id="androidappsrctestjavaappplainnoteslocktestkt"></a>

```kotlin
package app.plainnotes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

// The data key the disguise keeps is only usable with both the phrase and the PIN.
class LockTest {
    private val key = SecretKeySpec(Crypto.randomBytes(32), "AES")
    private val salt = Crypto.randomBytes(16)
    private val wrapped = Lock.wrap(key, "blue kettle", "4821", salt)

    @Test fun unwrapsWithPhraseAndPin() =
        assertArrayEquals(key.encoded, Lock.unwrap(wrapped, "blue kettle", "4821", salt)!!.encoded)

    @Test fun phraseIgnoresCaseAndSpacing() =
        assertArrayEquals(key.encoded, Lock.unwrap(wrapped, "  Blue   KETTLE ", "4821", salt)!!.encoded)

    @Test fun rejectsWrongPin() = assertNull(Lock.unwrap(wrapped, "blue kettle", "4822", salt))

    @Test fun rejectsWrongPhrase() = assertNull(Lock.unwrap(wrapped, "red kettle", "4821", salt))

    @Test fun rejectsOtherSalt() = assertNull(Lock.unwrap(wrapped, "blue kettle", "4821", Crypto.randomBytes(16)))

    @Test fun normalizes() = assertEquals("blue kettle", Lock.normalize("\tBlue \n Kettle  "))
}
```


## `android/build.gradle.kts`

<a id="androidbuildgradlekts"></a>

```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
```


## `android/gradle.properties`

<a id="androidgradleproperties"></a>

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```


## `android/settings.gradle.kts`

<a id="androidsettingsgradlekts"></a>

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Ghostline"
include(":app")
```
