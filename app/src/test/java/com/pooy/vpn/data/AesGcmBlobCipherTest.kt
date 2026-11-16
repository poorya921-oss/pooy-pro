package com.pooy.vpn.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Exercises the exact AES-256-GCM code used in production, with a software key instead of the Android Keystore. */
class AesGcmBlobCipherTest {
    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val key = newKey()
    private val cipher = AesGcmBlobCipher { key }

    @Test fun roundTrip() {
        val plain = "profiles {\"v\":1} ünïcode".toByteArray()
        assertArrayEquals(plain, cipher.decrypt(cipher.encrypt(plain)))
    }

    @Test fun blobLayoutIsIvThenCiphertextAndTag() {
        val plain = ByteArray(100)
        assertEquals(12 + 100 + 16, cipher.encrypt(plain).size)
    }

    @Test fun everyEncryptionUsesAFreshIv() {
        val ivs = (1..200).map { cipher.encrypt(byteArrayOf(1)).copyOfRange(0, 12).toList() }.toSet()
        assertEquals(200, ivs.size)
    }

    @Test fun samePlaintextGivesDifferentCiphertext() {
        val p = ByteArray(32)
        assertFalse(cipher.encrypt(p).contentEquals(cipher.encrypt(p)))
    }

    @Test fun tamperedCiphertextIsRejected() {
        val b = cipher.encrypt("secret".toByteArray())
        b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(b) }
    }

    @Test fun tamperedIvIsRejected() {
        val b = cipher.encrypt("secret".toByteArray())
        b[0] = (b[0].toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(b) }
    }

    @Test fun wrongKeyIsRejected() {
        val b = cipher.encrypt("secret".toByteArray())
        val other = newKey()
        assertThrows(GeneralSecurityException::class.java) { AesGcmBlobCipher { other }.decrypt(b) }
    }

    @Test fun emptyPlaintextRoundTrips() {
        assertEquals(0, cipher.decrypt(cipher.encrypt(ByteArray(0))).size)
    }

    @Test fun tooShortBlobIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt(ByteArray(10)) }
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt(ByteArray(27)) }
        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(ByteArray(28)) } // right size, wrong tag
    }
}
