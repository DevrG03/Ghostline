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
