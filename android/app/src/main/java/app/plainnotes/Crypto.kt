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
