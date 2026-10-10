package app.plainnotes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

// The data key the disguise keeps is only usable with both the phrase and the PIN.
class LockTest {
    private val key = SecretKeySpec(Crypto.randomBytes(32), "AES")
    private val salt = Crypto.randomBytes(16)
    private val wrapped = Lock.wrap(key, "blue kettle", "4821", salt)

    @Test fun unwrapsWithPhraseAndPin() =
        assertArrayEquals(key.encoded, Lock.unwrap(wrapped, "blue kettle", "4821", salt)!!.encoded)

    @Test fun phraseIgnoresCaseAndSpacing() =
        assertArrayEquals(key.encoded, Lock.unwrap(wrapped, "  Blue   KETTLE ", "4821", salt)!!.encoded)

    @Test fun rejectsWrongPin() = assertNull(Lock.unwrap(wrapped, "blue kettle", "4822", salt))

    @Test fun rejectsWrongPhrase() = assertNull(Lock.unwrap(wrapped, "red kettle", "4821", salt))

    @Test fun rejectsOtherSalt() = assertNull(Lock.unwrap(wrapped, "blue kettle", "4821", Crypto.randomBytes(16)))

    @Test fun normalizes() = assertEquals("blue kettle", Lock.normalize("\tBlue \n Kettle  "))
}
