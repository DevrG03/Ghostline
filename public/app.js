'use strict';

// How it works:
// - The invite link carries a random 256-bit secret after "#". Browsers never send
//   the "#..." part to the server.
// - From that secret we derive (HKDF) a room id, which is the only thing the server
//   sees, and an AES-GCM key that encrypts all signaling. So the server can't read
//   or forge the WebRTC handshake, and it can't sit in the middle.
// - Chat runs over a WebRTC data channel and calls over SRTP, directly between the
//   two devices (DTLS-encrypted, authenticated by the encrypted handshake).
// - Nothing goes into cookies, localStorage, IndexedDB or the URL history.
//
// STUN/TURN servers come from the server's /config (see server.js). With a TURN server
// configured, relay-only mode hides each person's IP address from the other.

const PAD_BLOCK = 256; // data channel messages are padded to a multiple of this many bytes

const $ = (id) => document.getElementById(id);
const newId = () => hex(crypto.getRandomValues(new Uint8Array(8)).buffer);
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

const state = {
  secret: null,
  roomId: null,
  key: null,
  ws: null,
  pc: null,
  dc: null,
  polite: false,
  makingOffer: false,
  ignoreOffer: false,
  ice: { iceServers: [], relayOnly: false },
  // Replay protection for signaling: a fresh id per peer session plus a strictly increasing counter.
  me: null,
  seq: 0,
  peerId: null,
  peerSeq: 0,
  code: null,
  verifiedCode: null,
  local: null,
  remote: new MediaStream(),
  call: 'idle', // idle | outgoing | incoming | active
  myVideo: false,
  peerVideo: false,
  facing: 'user',
};

// ---------- crypto ----------

async function deriveKeys(secret) {
  const raw = b64u.from(secret);
  if (raw.length < 16) throw new Error('bad secret');
  const base = await crypto.subtle.importKey('raw', raw, 'HKDF', false, ['deriveBits', 'deriveKey']);
  const salt = te.encode('ghostline-v1');
  const roomBits = await crypto.subtle.deriveBits(
    { name: 'HKDF', hash: 'SHA-256', salt, info: te.encode('room-id') }, base, 256);
  state.roomId = hex(roomBits);
  state.key = await crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt, info: te.encode('signal-key') },
    base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

async function seal(obj) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: te.encode(state.roomId) },
    state.key, te.encode(JSON.stringify(obj)));
  return b64u.to(iv) + '.' + b64u.to(ct);
}

async function unseal(str) {
  try {
    const [iv, ct] = str.split('.');
    const pt = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: b64u.from(iv), additionalData: te.encode(state.roomId) },
      state.key, b64u.from(ct));
    return JSON.parse(td.decode(pt));
  } catch {
    return null; // tampered or not from our peer: ignore
  }
}

// ---------- UI helpers ----------

function setStatus(text, kind = '') {
  $('statusText').textContent = text;
  $('dot').className = 'dot ' + kind;
}

function scrollChat() {
  const box = $('messages');
  box.scrollTop = box.scrollHeight;
}

function addMsg(text, who, ttl = 0) {
  const el = document.createElement('div');
  el.className = 'msg ' + who;
  el.textContent = text;
  const t = document.createElement('time');
  t.textContent = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) +
    (ttl ? ` · disappears in ${ttl >= 60 ? ttl / 60 + 'm' : ttl + 's'}` : '');
  el.append(t);
  $('messages').append(el);
  scrollChat();
  if (ttl) setTimeout(() => el.remove(), ttl * 1000);
}

function sysMsg(text) {
  const el = document.createElement('div');
  el.className = 'sys';
  el.textContent = text;
  $('messages').append(el);
  scrollChat();
}

function dcOpen() {
  return state.dc?.readyState === 'open';
}

function refreshButtons() {
  const ready = dcOpen();
  $('msgInput').disabled = !ready;
  $('sendBtn').disabled = !ready;
  $('voiceBtn').disabled = !ready || state.call !== 'idle';
  $('videoBtn').disabled = !ready || state.call !== 'idle';
}

function renderCall() {
  const { call } = state;
  $('call').hidden = call === 'idle' || call === 'incoming';
  $('incoming').hidden = call !== 'incoming';
  $('callLabel').textContent = call === 'outgoing' ? 'Calling…' : 'Voice call';
  const showRemote = call === 'active' && state.peerVideo;
  $('remoteVideo').style.visibility = showRemote ? 'visible' : 'hidden';
  $('avatar').hidden = showRemote;
  $('localVideo').hidden = !state.myVideo;
  $('localVideo').classList.toggle('back', state.facing === 'environment');
  $('camBtn').textContent = state.myVideo ? 'Camera off' : 'Camera on';
  $('flipBtn').hidden = !state.myVideo;
  const mic = state.local?.getAudioTracks()[0];
  $('micBtn').textContent = mic && !mic.enabled ? 'Unmute' : 'Mute';
  refreshButtons();
}

// ---------- signaling ----------

function connectSignaling() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/ws`);
  state.ws = ws;
  let queue = Promise.resolve();
  ws.onopen = () => ws.send(JSON.stringify({ type: 'join', room: state.roomId }));
  ws.onmessage = (e) => {
    queue = queue.then(() => onServerMessage(JSON.parse(e.data))).catch((err) => console.error(err));
  };
  ws.onclose = () => {
    if (state.ws !== ws) return;
    // The secret is no longer in the address bar, so a refresh would not rejoin.
    if (!dcOpen()) setStatus('Lost connection to the server. Open the invite link again to rejoin.', 'bad');
  };
}

// Every new peer session gets fresh signaling ids, so old messages can't be replayed into it.
function resetSession() {
  state.me = newId();
  state.seq = 0;
  state.peerId = null;
  state.peerSeq = 0;
}

async function onServerMessage(m) {
  if (['joined', 'peer-joined', 'peer-left'].includes(m.type)) resetSession();
  switch (m.type) {
    case 'joined':
      state.polite = m.peers > 0;
      if (state.polite) {
        setStatus('Connecting to the other person…');
        createPeer();
      } else {
        setStatus('Waiting for the other person to open the link…');
      }
      break;
    case 'peer-joined':
      teardownPeer();
      state.polite = false;
      setStatus('Connecting to the other person…');
      createPeer();
      setupDc(state.pc.createDataChannel('chat', { ordered: true }));
      break;
    case 'peer-left':
      teardownPeer();
      state.polite = false;
      sysMsg('The other person left.');
      setStatus('Waiting for the other person to open the link…');
      break;
    case 'full':
      setStatus('This room already has two people.', 'bad');
      break;
    case 'signal': {
      const data = await unseal(m.data);
      if (!data || typeof data.from !== 'string' || data.from === state.me) return;
      // Lock onto the first sender of this session and drop anything replayed or out of order.
      if (state.peerId && data.from !== state.peerId) return;
      if (!(Number.isSafeInteger(data.seq) && data.seq > state.peerSeq)) return;
      state.peerId = data.from;
      state.peerSeq = data.seq;
      await onSignal(data);
      break;
    }
  }
}

async function sendSignal(obj) {
  if (state.ws?.readyState !== WebSocket.OPEN) return;
  const data = await seal({ ...obj, from: state.me, seq: ++state.seq });
  state.ws.send(JSON.stringify({ type: 'signal', data }));
}

// ---------- WebRTC (perfect negotiation pattern) ----------

function createPeer() {
  const pc = new RTCPeerConnection({
    iceServers: state.ice.iceServers,
    iceTransportPolicy: state.ice.relayOnly ? 'relay' : 'all',
  });
  state.pc = pc;

  pc.onicecandidate = ({ candidate }) => candidate && sendSignal({ candidate });

  pc.onnegotiationneeded = async () => {
    try {
      state.makingOffer = true;
      await pc.setLocalDescription();
      await sendSignal({ description: pc.localDescription });
    } catch (err) {
      console.error(err);
    } finally {
      state.makingOffer = false;
    }
  };

  pc.ontrack = ({ track }) => {
    if (!state.remote.getTracks().includes(track)) state.remote.addTrack(track);
    const v = $('remoteVideo');
    if (v.srcObject !== state.remote) v.srcObject = state.remote;
    v.play().catch(() => {});
  };

  pc.ondatachannel = (e) => setupDc(e.channel);
  pc.onsignalingstatechange = () => pc.signalingState === 'stable' && updateSafetyCode();
  pc.onconnectionstatechange = () => {
    if (state.pc !== pc) return;
    if (pc.connectionState === 'disconnected') setStatus('Connection interrupted…', 'bad');
    else if (pc.connectionState === 'connected' && dcOpen()) setStatus(connectedText(), 'ok');
    else if (pc.connectionState === 'failed') {
      setStatus('Direct connection failed. The network may block it (a TURN server would help).', 'bad');
      pc.restartIce();
    }
  };
}

async function onSignal({ description, candidate }) {
  if (!state.pc) createPeer();
  const pc = state.pc;
  if (description) {
    const collision = description.type === 'offer' &&
      (state.makingOffer || pc.signalingState !== 'stable');
    state.ignoreOffer = !state.polite && collision;
    if (state.ignoreOffer) return;
    await pc.setRemoteDescription(description);
    if (description.type === 'offer') {
      await pc.setLocalDescription();
      await sendSignal({ description: pc.localDescription });
    }
  } else if (candidate) {
    try {
      await pc.addIceCandidate(candidate);
    } catch (err) {
      if (!state.ignoreOffer) console.error(err);
    }
  }
}

function teardownPeer() {
  endCall(false);
  state.dc?.close();
  state.pc?.close();
  state.dc = null;
  state.pc = null;
  state.remote.getTracks().forEach((t) => state.remote.removeTrack(t));
  state.code = null;
  renderSafety();
  refreshButtons();
}

const fingerprint = (sdp) => sdp?.match(/a=fingerprint:\S+ (\S+)/)?.[1]?.toUpperCase();

const connectedText = () =>
  'Connected · end-to-end encrypted' + (state.verifiedCode && state.code === state.verifiedCode ? ' · verified' : '');

function renderSafety() {
  const verified = !!state.code && state.code === state.verifiedCode;
  const changed = !!state.verifiedCode && !!state.code && !verified;
  $('safety').textContent = state.code || '—';
  $('safetyBox').classList.toggle('verified', verified);
  $('safetyBox').classList.toggle('changed', changed);
  $('verifyBtn').hidden = !state.code || verified;
  if (dcOpen()) setStatus(connectedText(), 'ok');
}

async function updateSafetyCode() {
  const a = fingerprint(state.pc?.localDescription?.sdp);
  const b = fingerprint(state.pc?.remoteDescription?.sdp);
  if (!a || !b) return;
  const h = new Uint8Array(await crypto.subtle.digest('SHA-256', te.encode([a, b].sort().join('|'))));
  const code = [0, 1, 2, 3]
    .map((i) => String(((h[i * 2] << 8) | h[i * 2 + 1]) % 10000).padStart(4, '0'))
    .join(' ');
  if (code === state.code) return;
  // Every page load gets new DTLS keys, so after verification any new code means the other
  // end reopened the link — or someone else with the link took their place.
  if (state.verifiedCode && code !== state.verifiedCode) {
    sysMsg('⚠ The safety code changed: the other person reconnected, or someone else joined with the link. ' +
      'Verify it again before saying anything sensitive.');
  }
  state.code = code;
  renderSafety();
}

// ---------- data channel: chat + call control ----------

function setupDc(ch) {
  state.dc = ch;
  ch.onopen = () => {
    setStatus(connectedText(), 'ok');
    sysMsg('Connected. Nothing here is saved anywhere.');
    $('invite').hidden = true; // don't leave the room key on screen once the other person is in
    refreshButtons();
  };
  ch.onclose = () => {
    if (state.dc !== ch) return;
    setStatus('Connection lost.', 'bad');
    refreshButtons();
  };
  ch.onmessage = (e) => {
    try {
      onPeerMessage(JSON.parse(e.data));
    } catch (err) {
      console.error(err);
    }
  };
}

// Pad every message to a multiple of PAD_BLOCK bytes, so its encrypted size on the network
// (visible to anyone watching, or to a TURN relay) doesn't reveal how long it is.
function sendPeer(obj) {
  if (!dcOpen()) return false;
  const len = te.encode(JSON.stringify(obj)).length + 7; // + ',"p":""'
  const pad = Math.ceil(len / PAD_BLOCK) * PAD_BLOCK - len;
  state.dc.send(JSON.stringify({ ...obj, p: '.'.repeat(pad) }));
  return true;
}

function onPeerMessage(m) {
  switch (m.t) {
    case 'msg':
      addMsg(String(m.text).slice(0, 4000), 'them', Math.max(0, Math.min(86400, Number(m.ttl) || 0)));
      break;
    case 'clear':
      $('messages').replaceChildren();
      sysMsg('Chat cleared by the other person.');
      break;
    case 'ring':
      if (state.call !== 'idle') return sendPeer({ t: 'busy' });
      state.call = 'incoming';
      state.peerVideo = !!m.video;
      $('incomingText').textContent = `Incoming ${m.video ? 'video' : 'voice'} call`;
      renderCall();
      break;
    case 'accept':
      if (state.call !== 'outgoing') return;
      state.peerVideo = !!m.video;
      state.call = 'active';
      attachTracks();
      renderCall();
      break;
    case 'decline':
    case 'busy':
      if (state.call !== 'outgoing') return;
      endCall(false);
      sysMsg(m.t === 'busy' ? 'The other person is busy.' : 'Call declined.');
      break;
    case 'cancel':
    case 'end':
      if (state.call === 'idle') return;
      endCall(false);
      sysMsg('Call ended.');
      break;
    case 'video':
      state.peerVideo = !!m.on;
      renderCall();
      break;
  }
}

// ---------- calls ----------

async function getMedia(video) {
  state.local = await navigator.mediaDevices.getUserMedia({
    audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    video: video ? { facingMode: state.facing, width: { ideal: 1280 }, height: { ideal: 720 } } : false,
  });
  state.myVideo = video;
  $('localVideo').srcObject = state.local;
}

// Reuse existing transceivers so repeated calls don't grow the connection.
async function attachTracks() {
  if (!state.local || !state.pc) return;
  for (const track of state.local.getTracks()) {
    const tr = state.pc.getTransceivers().find(
      (t) => t.receiver.track.kind === track.kind && !t.sender.track && !t.stopped);
    if (tr) {
      await tr.sender.replaceTrack(track);
      tr.sender.setStreams?.(state.local);
      if (tr.direction !== 'sendrecv') tr.direction = 'sendrecv';
    } else if (!state.pc.getSenders().some((s) => s.track === track)) {
      state.pc.addTrack(track, state.local);
    }
  }
}

async function startCall(video) {
  if (state.call !== 'idle' || !dcOpen()) return;
  try {
    await getMedia(video);
  } catch {
    return sysMsg('Could not access the microphone or camera.');
  }
  state.call = 'outgoing';
  sendPeer({ t: 'ring', video });
  renderCall();
}

async function acceptCall(video) {
  if (state.call !== 'incoming') return;
  try {
    await getMedia(video);
  } catch {
    sysMsg('Could not access the microphone or camera.');
    return declineCall();
  }
  state.call = 'active';
  await attachTracks();
  sendPeer({ t: 'accept', video });
  renderCall();
  $('remoteVideo').play().catch(() => {});
}

function declineCall() {
  sendPeer({ t: 'decline' });
  endCall(false);
}

function endCall(notify) {
  if (notify && state.call !== 'idle') sendPeer({ t: state.call === 'outgoing' ? 'cancel' : 'end' });
  state.local?.getTracks().forEach((t) => t.stop());
  state.local = null;
  state.pc?.getSenders().forEach((s) => s.track && s.replaceTrack(null).catch(() => {}));
  state.call = 'idle';
  state.myVideo = false;
  state.peerVideo = false;
  $('localVideo').srcObject = null;
  renderCall();
}

function toggleMic() {
  const mic = state.local?.getAudioTracks()[0];
  if (!mic) return;
  mic.enabled = !mic.enabled;
  renderCall();
}

async function toggleCam() {
  if (!state.local) return;
  let cam = state.local.getVideoTracks()[0];
  if (!cam) {
    try {
      const s = await navigator.mediaDevices.getUserMedia({ video: { facingMode: state.facing } });
      cam = s.getVideoTracks()[0];
      state.local.addTrack(cam);
      $('localVideo').srcObject = state.local;
      if (state.call === 'active') await attachTracks();
    } catch {
      return sysMsg('Could not access the camera.');
    }
    state.myVideo = true;
  } else {
    state.myVideo = !state.myVideo;
    cam.enabled = state.myVideo;
  }
  sendPeer({ t: 'video', on: state.myVideo });
  renderCall();
}

async function flipCam() {
  const old = state.local?.getVideoTracks()[0];
  if (!old) return;
  state.facing = state.facing === 'user' ? 'environment' : 'user';
  old.stop(); // many phones can't open two cameras at once
  state.local.removeTrack(old);
  try {
    const s = await navigator.mediaDevices.getUserMedia({ video: { facingMode: state.facing } });
    const cam = s.getVideoTracks()[0];
    state.local.addTrack(cam);
    $('localVideo').srcObject = state.local;
    const sender = state.pc?.getSenders().find((x) => x.track === old);
    if (sender) await sender.replaceTrack(cam);
    else if (state.call === 'active') await attachTracks();
  } catch {
    sysMsg('Could not switch camera.');
  }
  renderCall();
}

// ---------- room lifecycle ----------

function inviteLink() {
  return `${location.origin}${location.pathname}#${state.secret}`;
}

async function enterRoom(secret, showInvite = false) {
  if (!window.isSecureContext || !crypto.subtle) {
    $('landing').hidden = false;
    $('createBtn').disabled = true;
    $('landing').querySelector('.lead').textContent =
      'This page must be opened over HTTPS (or on localhost) for encryption to work.';
    return;
  }
  try {
    await deriveKeys(secret);
  } catch {
    $('landing').hidden = false;
    return;
  }
  try {
    const res = await fetch('/config', { cache: 'no-store', credentials: 'omit' });
    const cfg = await res.json();
    if (Array.isArray(cfg.iceServers)) state.ice = { iceServers: cfg.iceServers, relayOnly: !!cfg.relayOnly };
  } catch {
    // No config: direct connections only (works on the same network).
  }
  state.secret = secret;
  $('landing').hidden = true;
  $('room').hidden = false;
  $('inviteLink').value = inviteLink();
  $('invite').hidden = !showInvite;
  connectSignaling();
}

function leave() {
  teardownPeer();
  if (state.ws) {
    const ws = state.ws;
    state.ws = null;
    ws.close();
  }
  $('messages').replaceChildren();
  $('inviteLink').value = '';
  state.secret = state.key = state.roomId = state.verifiedCode = null;
  location.replace(location.pathname);
}

// ---------- wiring ----------

$('createBtn').onclick = () => {
  const secret = b64u.to(crypto.getRandomValues(new Uint8Array(32)));
  enterRoom(secret, true);
};
$('inviteBtn').onclick = () => ($('invite').hidden = !$('invite').hidden);
$('copyBtn').onclick = async () => {
  try {
    await navigator.clipboard.writeText(inviteLink());
    $('copyBtn').textContent = 'Copied';
  } catch {
    $('inviteLink').select();
  }
  setTimeout(() => ($('copyBtn').textContent = 'Copy'), 1500);
};
$('leaveBtn').onclick = leave;
$('verifyBtn').onclick = () => {
  state.verifiedCode = state.code;
  sysMsg('Safety code marked as verified. You will be warned if it changes.');
  renderSafety();
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

$('composer').onsubmit = (e) => {
  e.preventDefault();
  const input = $('msgInput');
  const text = input.value.trim();
  if (!text) return;
  const ttl = Number($('ttl').value) || 0;
  if (sendPeer({ t: 'msg', text, ttl })) {
    addMsg(text, 'me', ttl);
    input.value = '';
  }
};
$('clearBtn').onclick = () => {
  $('messages').replaceChildren();
  sendPeer({ t: 'clear' });
};

// Blank the page while it is in the background, so app-switcher thumbnails stay empty.
document.addEventListener('visibilitychange', () => document.body.classList.toggle('veil', document.hidden));
// Never let the browser keep this page in its back/forward cache.
window.addEventListener('pagehide', () => {
  teardownPeer();
  state.ws?.close();
});

// Boot: read the secret from the link, then remove it from the address bar and history entry.
{
  const secret = location.hash.slice(1);
  if (secret && /^[A-Za-z0-9_-]{22,}$/.test(secret)) {
    history.replaceState(null, '', location.pathname);
    enterRoom(secret);
  } else {
    if (secret) history.replaceState(null, '', location.pathname);
    $('landing').hidden = false;
  }
}
