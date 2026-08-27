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
    // Trail batch envelope (SPEC_T13 §2.2 / Phase-2 doc §9a): explicit random 12-byte IV
    // (a batch has no messageId), key = deriveConversationKey(...). Returns
    // Pair(base64(iv), base64(ciphertext||16-byte tag)) — the server splits the tag off.
    // -------------------------------------------------------------------------
    fun encryptTrailBatch(convKey: ByteArray, plaintext: ByteArray): Pair<String, String> {
        val iv = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(convKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext)
        return Pair(
            Base64.encodeToString(iv, Base64.NO_WRAP),
            Base64.encodeToString(ct, Base64.NO_WRAP)
        )
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

    // -------------------------------------------------------------------------
    // File crypto — HKDF with separate info, random 12-byte nonce
    // §5.4: file_key = HKDF(conversation_key, salt=zeroes, info="fshu-next-file-v1")
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Group key crypto
    // §5.5: group_key = random 32 bytes
    //       encrypted_for_member = nonce || AES-GCM(SHA-256(X25519(myPriv,memberPub)), groupKey)
    //       group message nonce  = SHA-256(messageId UTF-8)[0:12]
    // -------------------------------------------------------------------------

    fun generateGroupKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** Returns hex(nonce || ciphertext+tag). The AES key is SHA-256(X25519(myPriv, memberPub)). */
    fun encryptGroupKey(groupKey: ByteArray, memberPubHex: String, myPrivHex: String): String {
        val shared = x25519(myPrivHex, memberPubHex)
        val aesKey = MessageDigest.getInstance("SHA-256").digest(shared)
        val nonce  = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return (nonce + cipher.doFinal(groupKey)).toHex()
    }

    /** Reverses encryptGroupKey. senderPubHex = the pub key of whoever encrypted this (usually owner). */
    fun decryptGroupKey(encryptedHex: String, senderPubHex: String, myPrivHex: String): ByteArray? = try {
        val bytes  = encryptedHex.fromHex()
        val nonce  = bytes.copyOf(NONCE_BYTES)
        val ct     = bytes.copyOfRange(NONCE_BYTES, bytes.size)
        val shared = x25519(myPrivHex, senderPubHex)
        val aesKey = MessageDigest.getInstance("SHA-256").digest(shared)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.doFinal(ct)
    } catch (e: Exception) {
        Log.w(TAG, "decryptGroupKey: ${e.message}")
        null
    }

    /** Returns Pair(base64(nonce || ciphertext), base64(nonce)). Nonce is random 12 bytes prepended to ciphertext. */
    fun encryptGroupMessage(groupKey: ByteArray, plaintext: String): Pair<String, String> {
        val nonce  = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(groupKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val blob = nonce + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Pair(Base64.encodeToString(blob, Base64.NO_WRAP), Base64.encodeToString(nonce, Base64.NO_WRAP))
    }

    /** Decodes base64(nonce || ciphertext), extracts nonce from first 12 bytes, decrypts remainder. */
    fun decryptGroupMessage(groupKey: ByteArray, ciphertext: String): String? = try {
        val bytes  = Base64.decode(ciphertext, Base64.NO_WRAP)
        val nonce  = bytes.copyOf(NONCE_BYTES)
        val ct     = bytes.copyOfRange(NONCE_BYTES, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(groupKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.doFinal(ct).toString(Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w(TAG, "decryptGroupMessage: ${e.message}")
        null
    }

    fun deriveFileKey(conversationKey: ByteArray): ByteArray {
        val salt = ByteArray(32)
        val info = "fshu-next-file-v1".toByteArray(Charsets.UTF_8)
        return hkdf(conversationKey, salt, info, 32)
    }

    fun encryptBytes(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(plaintext)
    }

    fun decryptBytes(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.doFinal(ciphertext)
    } catch (e: Exception) {
        Log.w(TAG, "decryptBytes: ${e.message}")
        null
    }
}
