package app.ghostline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Vectors produced by the web app's code path (WebCrypto in Node), so the Android app
// is proven to derive the same room and decrypt what browsers send.
class CryptoTest {
    private val secret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val webRoomId = "4da9120812de3682bfa7e188a412d7d3caeeeba2774d0f1c9630fc6e6fdc1d1e"
    private val webSealed = "j1CN3FPFfSaLvT_5.fNaMy9eMb06vcTH2O1_7KwkIsj66ks6EgaSsdaxRQPQ0NCa8-S2inSF1"

    @Test fun derivesSameRoomIdAsWeb() = assertEquals(webRoomId, RoomKeys(secret).roomId)

    @Test fun decryptsWhatWebEncrypted() =
        assertEquals("{\"hello\":\"wörld\",\"seq\":1}", RoomKeys(secret).unseal(webSealed))

    @Test fun roundTrips() {
        val k = RoomKeys(secret)
        assertEquals("ping", k.unseal(k.seal("ping")))
    }

    @Test fun rejectsTamperedOrForeign() {
        assertNull(RoomKeys(secret).unseal(webSealed.dropLast(2) + "AA"))
        assertNull(RoomKeys(Crypto.b64u(ByteArray(32))).unseal(webSealed))
    }

    @Test fun safetyCodeMatchesWeb() = assertEquals(
        "8994 0998 1377 9789",
        safetyCode("v=0\r\na=fingerprint:sha-256 AA:BB:CC\r\n", "a=fingerprint:sha-256 11:22:33\r\n"),
    )

    @Test fun parsesInviteLinks() {
        val i = Invite.parse("https://ghostline.onrender.com/#$secret")!!
        assertEquals("https://ghostline.onrender.com", i.origin)
        assertEquals(secret, i.secret)
        assertEquals("http://localhost:3000", Invite.parse("http://localhost:3000/#$secret")!!.origin)
        assertEquals(i, Invite.find("Join me: https://ghostline.onrender.com/#$secret thanks"))
        assertNull(Invite.parse("https://example.com/#short"))
        assertNull(Invite.parse("ftp://example.com/#$secret"))
    }
}
