package app.ghostline

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// The Notes disguise. When it's on, the app opens as a plain notes list; adding a note that is the
// secret phrase asks for the PIN, and only the right PIN shows Ghostline. Both are kept as salted
// hashes in the encrypted store, never as text.
class Lock(context: Context) {
    private val store = Store(context)
    private var cfg: JSONObject? = try {
        store.read(FILE)?.let { JSONObject(it) }
    } catch (e: Exception) {
        Log.e("Lock", "could not read the disguise settings", e)
        null
    }

    val enabled get() = cfg != null

    fun isPhrase(text: String) = cfg?.let { matches(it, "phrase", normalize(text)) } ?: false
    fun checkPin(pin: String) = cfg?.let { matches(it, "pin", pin) } ?: false

    fun set(phrase: String, pin: String) {
        val salt = Crypto.randomBytes(16)
        val c = JSONObject()
            .put("salt", Crypto.b64u(salt))
            .put("phrase", Crypto.b64u(hash("phrase", normalize(phrase), salt)))
            .put("pin", Crypto.b64u(hash("pin", pin, salt)))
        store.write(FILE, c.toString())
        cfg = c
    }

    fun disable() {
        store.delete(FILE)
        cfg = null
    }

    private fun matches(c: JSONObject, field: String, value: String): Boolean {
        val salt = Crypto.unb64u(c.getString("salt"))
        return MessageDigest.isEqual(hash(field, value, salt), Crypto.unb64u(c.getString(field)))
    }

    private fun hash(field: String, value: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("$field:$value".toCharArray(), salt, 20_000, 256))
            .encoded

    companion object {
        private const val FILE = "lock"

        // so the keyboard's auto-capitals or a stray space don't stop the phrase working
        fun normalize(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
    }
}
