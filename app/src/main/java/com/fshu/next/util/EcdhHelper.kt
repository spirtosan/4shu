package com.fshu.next.util

import android.util.Base64
import android.util.Log
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Bouncy Castle X25519 + HKDF-SHA256 + AES-256-GCM.
 * All crypto for fshu-next 1-1 messages lives here.
 * No Google / Tink. Bouncy Castle lightweight API only for X25519; javax.crypto for AES.
 */
object EcdhHelper {
    private const val TAG = "EcdhHelper"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES  = 12

    // -------------------------------------------------------------------------
    // Hex helpers
    // -------------------------------------------------------------------------

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun String.fromHex(): ByteArray {
        require(length % 2 == 0) { "odd-length hex" }
        return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    // -------------------------------------------------------------------------
    // X25519 key generation  (Bouncy Castle lightweight API)
    // -------------------------------------------------------------------------

    data class KeyPair(val privateKeyHex: String, val publicKeyHex: String)

    fun generateKeyPair(): KeyPair {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(SecureRandom()))
        val pair = gen.generateKeyPair()
        val priv = (pair.getPrivate() as X25519PrivateKeyParameters).encoded
        val pub  = (pair.getPublic()  as X25519PublicKeyParameters).encoded
        return KeyPair(priv.toHex(), pub.toHex())
    }

    // -------------------------------------------------------------------------
    // X25519 raw key agreement
    // -------------------------------------------------------------------------

    private fun x25519(myPrivHex: String, peerPubHex: String): ByteArray {
        val priv = X25519PrivateKeyParameters(myPrivHex.fromHex())
        val pub  = X25519PublicKeyParameters(peerPubHex.fromHex())
        val agr  = X25519Agreement()
        agr.init(priv)
        val secret = ByteArray(agr.agreementSize)
        agr.calculateAgreement(pub, secret, 0)
        return secret
    }

    // -------------------------------------------------------------------------
    // HKDF-SHA256  (RFC 5869 — pure javax.crypto.Mac, no extra deps)
    // -------------------------------------------------------------------------

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val out  = ByteArray(len)
        var prev = ByteArray(0)
        var off  = 0
        var ctr  = 1
        while (off < len) {
            val block = hmacSha256(prk, prev + info + byteArrayOf(ctr.toByte()))
            val copy  = minOf(block.size, len - off)
            block.copyInto(out, off, 0, copy)
            off += copy; prev = block; ctr++
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Conversation key derivation
    // §5.2: HKDF(ikm=shared, salt=SHA256("lo:hi"), info="fshu-next-1-1-v1", len=32)
    // -------------------------------------------------------------------------

    fun deriveConversationKey(
        myPrivHex: String, peerPubHex: String,
        me: String, peer: String
    ): ByteArray {
        val shared    = x25519(myPrivHex, peerPubHex)
        val lo        = minOf(me, peer)
        val hi        = maxOf(me, peer)
        val pairBytes = "$lo:$hi".toByteArray(Charsets.UTF_8)
        val salt      = MessageDigest.getInstance("SHA-256").digest(pairBytes)
        val info      = "fshu-next-1-1-v1".toByteArray(Charsets.UTF_8)
        return hkdf(shared, salt, info, 32)
    }

    // -------------------------------------------------------------------------
    // Nonce: SHA-256(messageId as big-endian 8 bytes)[0:12]
    // -------------------------------------------------------------------------

    private fun nonce(messageId: Long): ByteArray {
        val buf = ByteBuffer.allocate(8)
        buf.putLong(messageId)
        return MessageDigest.getInstance("SHA-256").digest(buf.array()).copyOf(NONCE_BYTES)
    }

    // -------------------------------------------------------------------------
    // AES-256-GCM  (javax.crypto)
    // -------------------------------------------------------------------------

    fun encrypt(key: ByteArray, messageId: Long, plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce(messageId)))
        return Base64.encodeToString(
            cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    fun decrypt(key: ByteArray, messageId: Long, ciphertext: String): String? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce(messageId)))
        cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w(TAG, "decrypt msgId=$messageId: ${e.message}")
        null
    }
}
