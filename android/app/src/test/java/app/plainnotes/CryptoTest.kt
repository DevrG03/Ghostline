package app.plainnotes

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

    // Keys and expected secret produced by the web app's pairing code path (WebCrypto).
    @Test fun pairingSecretMatchesWeb() {
        val priv = java.security.KeyFactory.getInstance("EC")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode("MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgB5PTdZ7yRlI6T6GNvRxS+5igBELI3umC4HK0dcVtaHahRANCAASATIZ+wazmXKnZLIbM0yF5qoLIa4LqlxnEiq4+9km9vdKYDl6pjuX8p2oTrEYVVnmWHNUdqy09sxCMPgrkvNC9")))
        val aRaw = Crypto.unb64u("BIBMhn7BrOZcqdkshszTIXmqgshrguqXGcSKrj72Sb290pgOXqmO5fynahOsRhVWeZYc1R2rLT2zEIw-CuS80L0")
        val params = (Pairing.newKeyPair().public as java.security.interfaces.ECPublicKey).params
        val aPub = java.security.KeyFactory.getInstance("EC").generatePublic(
            java.security.spec.ECPublicKeySpec(
                java.security.spec.ECPoint(java.math.BigInteger(1, aRaw.copyOfRange(1, 33)), java.math.BigInteger(1, aRaw.copyOfRange(33, 65))),
                params,
            ),
        ) as java.security.interfaces.ECPublicKey
        assertEquals("BIBMhn7BrOZcqdkshszTIXmqgshrguqXGcSKrj72Sb290pgOXqmO5fynahOsRhVWeZYc1R2rLT2zEIw-CuS80L0", Crypto.b64u(Pairing.rawPublic(aPub)))
        assertEquals("ZrpW9-P23rJY2xyFnsl9gzedxmgJUexhmPWbKat0UkU", Pairing.roomSecret(priv, aPub, Crypto.unb64u("BBIpxgNL2AjJj9nxccN_M_iPrbUAgyCnmlpj0-kGyprbFoLmjjdduCgbdv9saEp9XED5RhOCDzYsuLMNsDPkQ9c")))
    }

    @Test fun pairingBothSidesAgree() {
        val a = Pairing.newKeyPair()
        val b = Pairing.newKeyPair()
        val pa = a.public as java.security.interfaces.ECPublicKey
        val pb = b.public as java.security.interfaces.ECPublicKey
        assertEquals(
            Pairing.roomSecret(a.private, pa, Pairing.rawPublic(pb)),
            Pairing.roomSecret(b.private, pb, Pairing.rawPublic(pa)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun pairingRejectsPointOffCurve() {
        val a = Pairing.newKeyPair()
        val bad = ByteArray(65).also { it[0] = 4; it[64] = 1 }
        Pairing.roomSecret(a.private, a.public as java.security.interfaces.ECPublicKey, bad)
    }

    @Test fun codes() {
        assertEquals("KQDMWP", Pairing.normalizeCode("kqd-mwp "))
        assertEquals("KQD-MWP", Pairing.formatCode("KQDMWP"))
    }
}
