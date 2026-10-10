package app.ghostline

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

// Rooms and chat history, saved as files in the app's private storage, each encrypted with an
// AES key that lives in the Android Keystore and can't be exported.
class Store(context: Context) {
    private val dir = File(context.filesDir, "data").apply { mkdirs() }
    private val key: SecretKey = loadKey()

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
        return try {
            val bytes = f.readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
            String(c.doFinal(bytes, 12, bytes.size - 12))
        } catch (e: Exception) {
            Log.e("Store", "could not read $name", e)
            null
        }
    }

    fun write(name: String, text: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key) // the Keystore picks a fresh random IV
        val out = c.iv + c.doFinal(text.toByteArray())
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(out)
        tmp.renameTo(File(dir, name)) // atomic replace
    }

    fun delete(name: String) {
        File(dir, name).delete()
    }

    companion object {
        private const val ALIAS = "ghostline-store"
    }
}
