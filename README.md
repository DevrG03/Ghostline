# Ghostline

Private chat, voice and video between two people: in the browser, or with the Android app
in [android/](android/).

**[Download for Android](https://github.com/DevrG03/Ghostline/releases/latest)**: the latest
signed APK (Android 8.0 or newer), with install steps and checksums on the release page.

> The complete source of every file is also collected in one place, verbatim, in
> [SOURCE.md](SOURCE.md).

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

## Roles

Whoever taps **New room** is the room's **owner**; whoever joins with the code is a **participant**.

- **Owner** can **end the room for both** (history wiped on both devices) and **remove the participant**
  (they're kicked and their copy is deleted; the owner keeps the history as a read-only archive).
- **Participant** can **leave** (deletes their own copy). Both can chat, call and verify the safety code.
- Roles are enforced by each app, not by the server (the server has no trusted identity of who is
  who), so they're a usability feature, not a hard security boundary. "End" and "remove" reach the
  other side while they're online, or the next time they connect.

## Notes disguise (Android)

The Android app installs as **Notes**, with a notepad icon. On the home screen, tap
**Disguise as Notes** to choose a secret phrase and a PIN. From then on the app opens as a working
notes list. To get in, add a note whose text is the secret phrase (capitals and extra spaces don't
matter), then enter the PIN. A wrong PIN goes back to the notes without saying anything.

- It locks again whenever you leave the app, except during a call. While it's locked you're offline,
  so the other person sees you as offline and messages arrive the next time you open it.
- Your rooms are encrypted with a random data key that is stored only wrapped by a key derived
  from the phrase and PIN (PBKDF2), so without both the files can't be read, even by the app.
  A wrong PIN fails to unwrap it; no hash of the PIN is stored. The phrase has its own quicker
  salted hash so a note can be recognised as it's added. Changing the PIN only re-wraps the key;
  turning the disguise off moves the data back to the Keystore key.
- The note box turns off keyboard learning, so the phrase isn't saved as a suggestion.
- Nothing the phone shows names Ghostline: the package is `app.plainnotes`, and the Keystore alias,
  log tag, call track names and stored file names are neutral too. The screens you see once
  unlocked, and the protocol constants shared with the web app, still say Ghostline, so someone
  who unpacks the APK can tell what it is.
- If you forget the phrase or PIN, nobody can recover your rooms. The only way back in is to
  clear the app's data, which deletes them.
- Your server's address is still visible to your network, in DNS and the TLS handshake, even over
  HTTPS. Give the server a neutral name, since an `onrender.com` subdomain is set from the
  service's name when it's created.

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
