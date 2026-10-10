# Ghostline

Private chat, voice and video between two people: in the browser, or with the Android app
in [android/](android/).

## Run locally

    npm install
    npm start            # http://127.0.0.1:3000

Open the page and tap **New room**. You get a 6-letter code like `KQD-MWP`; the other person
taps **Join with code** and types it. The room is then saved on both devices (and works between
the website and the Android app) until one of you taps **Leave room**.

## Use from phones

Camera, microphone and the encryption APIs only work over **HTTPS**. Pick one:

- Quick test: `cloudflared tunnel --url http://localhost:3000` (gives you a temporary https URL).
- Real deploy: run on any VPS/host with `HOST=0.0.0.0 PORT=3000 npm start` behind an HTTPS proxy
  (e.g. Caddy: `your.domain { reverse_proxy localhost:3000 }`).

## What is protected

- No accounts, no server database, no logs, no cookies.
- **Pairing:** the code is single use, expires after 10 minutes, and join attempts are limited
  per network. The first person to enter it joins. The two devices then run an ECDH key exchange,
  so the room secret never crosses the network.
- **The server sees the code**, so a malicious server could try to sit in the middle of that
  exchange. Comparing the safety code once (below) rules this out.
- **Saved rooms:** rooms and chat history are stored only on each device, encrypted (IndexedDB with
  a non-exportable key on the web; the Android Keystore in the app). Messages typed while the
  other person is offline wait on your device and are delivered later (✓✓ when they arrive).
- All signaling is AES-256-GCM encrypted with a key derived from the room secret, so the server
  cannot read or tamper with the handshake.
- Chat and calls flow directly device-to-device over WebRTC (DTLS/SRTP).
- Safety code: compare it out loud; matching codes confirm nobody is in the middle. Each device
  keeps one key per room, so the code stays the same across reconnects. Mark it verified and
  you'll be warned if it ever changes.
- Replayed or reordered signaling messages are rejected.
- Chat messages are padded to 256-byte blocks so their encrypted size doesn't reveal their exact length.
- Optional disappearing messages, "Clear" on both sides, page blanks itself in the app switcher
  (best effort: some phones take the snapshot before the page can blank itself).
- The server survives malformed input and enforces connection, rate and pairing-attempt limits.

## Hiding IP addresses (TURN relay)

By default the browsers connect directly, so each side can see the other's IP address, and
Google's public STUN server (`stun.l.google.com`) sees both IPs. To avoid both, run your own
TURN server (e.g. [coturn](https://github.com/coturn/coturn) with `use-auth-secret` and
`static-auth-secret=...`) and start Ghostline with:

    TURN_URLS=turns:turn.your.domain:5349 TURN_SECRET=<same secret> npm start

With TURN configured, **relay-only mode is on by default**: all traffic goes through your relay
(still end-to-end encrypted), no IPs reach the other person, and no STUN server is contacted.
The server hands out short-lived (12h) TURN credentials.

| Variable | Default | Meaning |
| --- | --- | --- |
| `STUN_URLS` | `stun:stun.l.google.com:19302` | Comma-separated; set to empty to use no STUN |
| `TURN_URLS` | — | Comma-separated TURN/TURNS urls |
| `TURN_SECRET` | — | coturn shared secret (preferred), or `TURN_USERNAME` + `TURN_CREDENTIAL` |
| `RELAY_ONLY` | on if TURN is set | `0` allows direct connections again |
| `MAX_CLIENTS` | `500` | Max simultaneous WebSocket connections |

## What the server operator can still see

Never message content, the room key or call media. But like any server, it sees connection
metadata: which IP addresses joined the same (hashed) room, and when. In relay-only mode the
TURN server sees the same. If you use `cloudflared`, Cloudflare sees it too.

## What NO website or app can protect against

- Spyware/stalkerware already on the phone that records the screen, keyboard, microphone or
  notifications. If the device is compromised, use a different, trusted device.
- The keyboard app: third-party keyboards can log what you type. Use the system keyboard.
- Browser history / sync: use a private (incognito) tab.
- The other person screenshotting or recording.
- Anyone who gets a pairing code before the intended person: share it over a trusted channel,
  and verify the safety code.
