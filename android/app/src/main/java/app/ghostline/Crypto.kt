package app.ghostline

import java.io.ByteArrayOutputStream
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

data class Invite(val origin: String, val secret: String) {
    val link get() = "$origin/#$secret"

    companion object {
        private val SECRET = Regex("^[A-Za-z0-9_-]{22,}$")
        private val LINK = Regex("^(https?)://([A-Za-z0-9.-]+)(:\\d+)?(/[^#\\s]*)?#([A-Za-z0-9_-]{22,})$")

        fun parse(text: String): Invite? {
            val m = LINK.find(text.trim()) ?: return null
            val (scheme, host, port, _, secret) = m.destructured
            if (!SECRET.matches(secret)) return null
            return Invite("$scheme://$host$port", secret)
        }

        fun find(text: String): Invite? =
            Regex("https?://\\S+#[A-Za-z0-9_-]{22,}").find(text)?.value?.let { parse(it) }

        fun create(origin: String) = Invite(origin, Crypto.b64u(Crypto.randomBytes(32)))
    }
}
