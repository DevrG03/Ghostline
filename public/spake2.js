'use strict';

// SPAKE2 (RFC 9382), ciphersuite P256-SHA256-HKDF-HMAC, for pairing two devices with a short
// code. Each side learns a strong shared key only if both typed the same code; a wrong guess
// (by an attacker or by the server relaying the messages) gets exactly one try per code.
//
// P-256 arithmetic is done here with BigInt because WebCrypto has no raw point operations.
// It is checked against the RFC's test vectors (see test/spake2.test.js).

const Spake2 = (() => {
  const P = 2n ** 256n - 2n ** 224n + 2n ** 192n + 2n ** 96n - 1n;
  const N = 0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551n; // group order
  const B = 0x5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604bn;
  const G = {
    x: 0x6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296n,
    y: 0x4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5n,
  };
  const te = new TextEncoder();

  const mod = (a, m = P) => ((a % m) + m) % m;
  const pow = (b, e, m = P) => {
    let r = 1n;
    b = mod(b, m);
    while (e > 0n) {
      if (e & 1n) r = (r * b) % m;
      b = (b * b) % m;
      e >>= 1n;
    }
    return r;
  };
  const inv = (a) => pow(a, P - 2n);

  // Points are {x, y} or null for the point at infinity.
  const add = (p, q) => {
    if (!p) return q;
    if (!q) return p;
    if (p.x === q.x) {
      if (mod(p.y + q.y) === 0n) return null;
      const l = mod(3n * p.x * p.x - 3n) * inv(2n * p.y) % P; // a = -3
      const x = mod(l * l - 2n * p.x);
      return { x, y: mod(l * (p.x - x) - p.y) };
    }
    const l = mod(q.y - p.y) * inv(mod(q.x - p.x)) % P;
    const x = mod(l * l - p.x - q.x);
    return { x, y: mod(l * (p.x - x) - p.y) };
  };
  const neg = (p) => (p ? { x: p.x, y: mod(-p.y) } : null);
  const mul = (k, p) => {
    let r = null;
    for (let i = 255n; i >= 0n; i--) {
      r = add(r, r);
      if ((k >> i) & 1n) r = add(r, p);
    }
    return r;
  };
  const onCurve = (p) => !!p && p.x < P && p.y < P && mod(p.y * p.y - (p.x ** 3n - 3n * p.x + B)) === 0n;

  const toBig = (bytes) => bytes.reduce((a, b) => (a << 8n) | BigInt(b), 0n);
  const toBytes = (n, len = 32) => {
    const out = new Uint8Array(len);
    for (let i = len - 1; i >= 0; i--, n >>= 8n) out[i] = Number(n & 0xffn);
    return out;
  };
  const hexBytes = (h) => Uint8Array.from(h.match(/../g), (b) => parseInt(b, 16));

  const encode = (p) => {
    const out = new Uint8Array(65);
    out[0] = 4;
    out.set(toBytes(p.x), 1);
    out.set(toBytes(p.y), 33);
    return out;
  };
  const decode = (bytes) => {
    let p;
    if (bytes.length === 65 && bytes[0] === 4) {
      p = { x: toBig(bytes.subarray(1, 33)), y: toBig(bytes.subarray(33)) };
    } else if (bytes.length === 33 && (bytes[0] === 2 || bytes[0] === 3)) {
      const x = toBig(bytes.subarray(1));
      let y = pow(mod(x ** 3n - 3n * x + B), (P + 1n) / 4n);
      if ((y & 1n) !== BigInt(bytes[0] & 1)) y = mod(-y);
      p = { x, y };
    }
    if (!onCurve(p)) throw new Error('invalid point');
    return p;
  };

  // RFC 9382 section 6, P-256
  const M = decode(hexBytes('02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f'));
  const Nn = decode(hexBytes('03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49'));

  const concat = (...parts) => {
    const out = new Uint8Array(parts.reduce((a, p) => a + p.length, 0));
    let o = 0;
    for (const p of parts) out.set(p, (o += p.length) - p.length);
    return out;
  };
  // len(x) || x, with an 8-byte little-endian length
  const lp = (b) => {
    const len = new Uint8Array(8);
    new DataView(len.buffer).setUint32(0, b.length, true);
    return concat(len, b);
  };

  const sha256 = async (b) => new Uint8Array(await crypto.subtle.digest('SHA-256', b));
  const hkdf = async (ikm, salt, info, bytes) => {
    const k = await crypto.subtle.importKey('raw', ikm, 'HKDF', false, ['deriveBits']);
    return new Uint8Array(await crypto.subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt, info }, k, bytes * 8));
  };
  const hmac = async (key, data) => {
    const k = await crypto.subtle.importKey('raw', key, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
    return new Uint8Array(await crypto.subtle.sign('HMAC', k, data));
  };
  const equal = (a, b) => a.length === b.length && a.reduce((d, v, i) => d | (v ^ b[i]), 0) === 0;

  const randomScalar = () => {
    for (;;) {
      const k = toBig(crypto.getRandomValues(new Uint8Array(32)));
      if (k > 0n && k < N) return k;
    }
  };

  // One side of the exchange. role is 'A' (room creator) or 'B' (joiner); w is a scalar.
  // opts.idA / opts.idB / opts.aad are byte strings (may be empty); opts.scalar is for tests.
  function start(role, w, opts = {}) {
    const idA = opts.idA || new Uint8Array(0);
    const idB = opts.idB || new Uint8Array(0);
    const aad = opts.aad || new Uint8Array(0);
    const s = opts.scalar ?? randomScalar();
    const mine = add(mul(s, G), mul(w, role === 'A' ? M : Nn));
    const message = encode(mine);

    async function finish(peerMessage) {
      const theirs = decode(peerMessage);
      const unblinded = add(theirs, neg(mul(w, role === 'A' ? Nn : M)));
      const K = mul(s, unblinded); // cofactor h = 1 for P-256
      if (!K) throw new Error('invalid exchange');
      const [pA, pB] = role === 'A' ? [message, peerMessage] : [peerMessage, message];
      const TT = concat(lp(idA), lp(idB), lp(pA), lp(pB), lp(encode(K)), lp(toBytes(w)));
      const h = await sha256(TT);
      const Ke = h.slice(0, 16);
      const Ka = h.slice(16);
      const kc = await hkdf(Ka, new Uint8Array(0), concat(te.encode('ConfirmationKeys'), aad), 32);
      const [KcA, KcB] = [kc.slice(0, 16), kc.slice(16)];
      const cA = await hmac(KcA, TT);
      const cB = await hmac(KcB, TT);
      return {
        key: Ke,
        confirmation: role === 'A' ? cA : cB,
        // true only if the other side derived the same keys (i.e. used the same code)
        verify: (theirConfirmation) => equal(theirConfirmation, role === 'A' ? cB : cA),
        _test: { K: encode(K), TT, Ke, Ka, KcA, KcB, cA, cB },
      };
    }
    return { message, finish };
  }

  // The password scalar from the pairing code. Brute force is pointless here (each guess
  // needs a live exchange), so a fast KDF is fine; the slot is mixed in as a salt.
  async function passwordScalar(code, slot) {
    const bytes = await hkdf(te.encode(code), te.encode('ghostline-pair-v1'), te.encode(String(slot)), 48);
    return mod(toBig(bytes), N);
  }

  return { start, passwordScalar, _internal: { toBig, toBytes, hexBytes, encode } };
})();

if (typeof module !== 'undefined') module.exports = Spake2;
