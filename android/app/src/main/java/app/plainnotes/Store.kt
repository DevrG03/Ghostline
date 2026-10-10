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
