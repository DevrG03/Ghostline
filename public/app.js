'use strict';

// How it works:
// - Two devices pair once with a short code (SPAKE2, see spake2.js). That gives both a strong
//   random room secret which never reaches the server. The room is then saved on each device.
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
      if (m.mine && m.status === 'pending' && this.sendPeer({ t: 'msg', id: m.id, text: m.text, ttl: m.ttl, ts: m.ts })) {
        m.status = 'sent';
        changed = true;
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
      case 'bye':
        this.rec.ended = true;
        saveRooms();
        net.send({ type: 'leave', room: this.id });
        this.addSys('The other person left this room. You can delete it from the ⋯ menu.');
        this.teardown();
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

const CODE_DIGITS = 6;
const PAIR_IDS = {
  idA: te.encode('ghostline-creator'),
  idB: te.encode('ghostline-joiner'),
  aad: te.encode('ghostline-v2'),
};

const pairing = {
  mode: null, // 'create' | 'join'
  slot: null,
  code: null,
  exchange: null,
  result: null,
  secret: null,
  done: false,
  expiresAt: 0,
  timer: null,

  reset() {
    clearInterval(this.timer);
    Object.assign(this, { mode: null, slot: null, code: null, exchange: null, result: null, secret: null, done: false });
  },

  randomCode() {
    const limit = Math.floor(2 ** 32 / 10 ** CODE_DIGITS) * 10 ** CODE_DIGITS; // uniform digits
    for (;;) {
      const n = crypto.getRandomValues(new Uint32Array(1))[0];
      if (n < limit) return String(n % 10 ** CODE_DIGITS).padStart(CODE_DIGITS, '0');
    }
  },

  format(slot, code) {
    return `${slot}-${code.slice(0, 3)}-${code.slice(3)}`;
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
    const digits = text.replace(/\D/g, '');
    if (digits.length < CODE_DIGITS + 2 || digits.length > CODE_DIGITS + 3) {
      return pairStatus('Enter the whole code, like 17-482-913.', true);
    }
    this.reset();
    this.mode = 'join';
    this.slot = Number(digits.slice(0, -CODE_DIGITS));
    this.code = digits.slice(-CODE_DIGITS);
    pairStatus('Connecting…');
    if (!net.send({ type: 'pair-join', slot: this.slot })) {
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

  sendData(obj) {
    net.send({ type: 'pair-msg', data: JSON.stringify(obj) });
  },

  async onServer(m) {
    if (!this.mode) return;
    switch (m.type) {
      case 'pair-created':
        this.slot = m.slot;
        this.code = this.randomCode();
        this.expiresAt = Date.now() + (m.expiresIn || 600000);
        $('pairCode').textContent = this.format(this.slot, this.code);
        pairStatus('Waiting for the other person to enter the code…');
        this.timer = setInterval(() => this.tick(), 1000);
        this.tick();
        break;
      case 'pair-peer': {
        pairStatus('Checking the code…');
        const w = await Spake2.passwordScalar(this.code, this.slot);
        this.exchange = Spake2.start(this.mode === 'create' ? 'A' : 'B', w, PAIR_IDS);
        if (this.mode === 'create') this.sendData({ pake: b64u.to(this.exchange.message) });
        break;
      }
      case 'pair-msg': {
        let d;
        try {
          d = JSON.parse(m.data);
        } catch {
          return;
        }
        if (d.pake && this.exchange && !this.result) {
          if (this.mode === 'join') this.sendData({ pake: b64u.to(this.exchange.message) });
          try {
            this.result = await this.exchange.finish(b64u.from(d.pake));
          } catch {
            return this.fail();
          }
          this.sendData({ conf: b64u.to(this.result.confirmation) });
        } else if (d.conf && this.result && !this.done) {
          if (!this.result.verify(b64u.from(d.conf))) return this.fail();
          await this.succeed();
        }
        break;
      }
      case 'pair-ended': {
        if (this.done) return;
        const reasons = {
          'no-such-code': 'No room is waiting for that code. Check the number, or ask for a new code.',
          'too-many-attempts': 'Too many attempts from this network. Wait 10 minutes and try again.',
          expired: 'This code expired. Get a new one.',
          'peer-gone': 'The other person closed the pairing screen.',
          'wrong-code': "The codes didn't match. For safety a code can't be retried, so get a new one.",
          cancelled: 'The other person cancelled.',
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

  fail() {
    net.send({ type: 'pair-end', reason: 'wrong-code' });
    pairStatus("The codes didn't match. For safety a code can't be retried, so get a new one.", true);
    $('pairCode').textContent = '— — —';
    clearInterval(this.timer);
    this.mode = null;
  },

  async succeed() {
    this.done = true;
    clearInterval(this.timer);
    net.send({ type: 'pair-end', reason: 'done' });
    // SPAKE2's shared key -> the room secret (the same format the room keys are derived from)
    const k = await crypto.subtle.importKey('raw', this.result.key, 'HKDF', false, ['deriveBits']);
    const bits = await crypto.subtle.deriveBits(
      { name: 'HKDF', hash: 'SHA-256', salt: te.encode('ghostline-v2'), info: te.encode('room-secret') }, k, 256);
    this.secret = b64u.to(bits);
    showPairPage('name');
    pairStatus('');
    $('roomNameInput').value = '';
    $('roomNameInput').focus();
  },

  tick() {
    const left = Math.max(0, Math.round((this.expiresAt - Date.now()) / 1000));
    $('pairExpiry').textContent = `Expires in ${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
  },
};

async function addRoom(secret, name) {
  const keys = await roomKeys(secret);
  if (rooms.has(keys.id)) return rooms.get(keys.id);
  let cert = null;
  try {
    cert = await RTCPeerConnection.generateCertificate({ name: 'ECDSA', namedCurve: 'P-256' });
    await store.raw('put', 'cert:' + keys.id, cert);
  } catch {
    // can't store certificates in this browser: the safety code will change on each reconnect
  }
  const rec = { id: keys.id, secret, name: name || 'Room', created: Date.now(), verifiedCode: null, unread: 0, ended: false };
  const room = new Room(rec, keys, [], cert);
  room.addSys('Paired. Messages are end-to-end encrypted and saved only on your devices.');
  rooms.set(room.id, room);
  saveRooms();
  room.join();
  return room;
}

async function deleteRoom(room) {
  if (call.room === room) endCall(true);
  room.sendPeer({ t: 'bye' });
  await sleep(150); // let the goodbye go out before the channel closes
  net.send({ type: 'leave', room: room.id });
  room.teardown();
  for (const t of room.expiryTimers.values()) clearTimeout(t);
  rooms.delete(room.id);
  saveRooms();
  store.del('msgs:' + room.id);
  store.del('cert:' + room.id);
  if (current === room) showHome();
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
  $('leaveBtn').textContent = room.rec.ended ? 'Delete room' : 'Leave room';
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
$('pairEnter').onsubmit = (e) => {
  e.preventDefault();
  pairing.startJoin($('codeInput').value);
};
$('pairName').onsubmit = async (e) => {
  e.preventDefault();
  if (!pairing.secret) return;
  const room = await addRoom(pairing.secret, $('roomNameInput').value.trim() || 'Room');
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
$('leaveBtn').onclick = async () => {
  $('menu').hidden = true;
  const room = current;
  const sure = await ask(room.rec.ended
    ? { title: 'Delete this room?', text: 'Its chat history is deleted from this device.', ok: 'Delete', danger: true }
    : {
      title: 'Leave this room?',
      text: 'The room and its chat history are deleted from this device. ' +
        (room.dcOpen()
          ? 'The other person is told you left. '
          : 'The other person is offline, so they won’t be told; they’ll just see you as offline. ') +
        'To talk again you’ll need a new code.',
      ok: 'Leave',
      danger: true,
    });
  if (sure) deleteRoom(room);
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
