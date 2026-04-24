package com.fshu.util

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoHelper {

    // Hard-coded pepper — identical on every device.
    // Do NOT change this value; changing it invalidates all existing derived keys.
    private val PEPPER = byteArrayOf(
        0x3E.toByte(), 0x8A.toByte(), 0xF4.toByte(), 0x1C.toByte(),
        0x7D.toByte(), 0x52.toByte(), 0xB9.toByte(), 0xE6.toByte(),
        0x4F.toByte(), 0xA3.toByte(), 0x2B.toByte(), 0x0D.toByte(),
        0xC8.toByte(), 0x91.toByte(), 0x6E.toByte(), 0x35.toByte(),
        0xF7.toByte(), 0x4C.toByte(), 0x18.toByte(), 0xAD.toByte(),
        0x83.toByte(), 0x5B.toByte(), 0xE0.toByte(), 0x29.toByte(),
        0x76.toByte(), 0xD4.toByte(), 0x9F.toByte(), 0x41.toByte(),
        0xCA.toByte(), 0x07.toByte(), 0xB2.toByte(), 0x68.toByte()
    )

    // -------------------------------------------------------------------------
    // Debug file log
    // -------------------------------------------------------------------------

    @Volatile private var debugLogFile: File? = null
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    fun initDebugLog(@Suppress("UNUSED_PARAMETER") context: Context) { /* debug logging disabled
        debugLogFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "crypto_debug.txt")
        val pepperHex = PEPPER.toHex()
        Log.d("Crypto", "pepper=$pepperHex")
        writeDebug("${tsFmt.format(Date())} PEPPER=$pepperHex")
    */ }

    private fun writeDebug(msg: String) {
        val file = debugLogFile ?: return
        try {
            synchronized(this) { file.appendText(msg + "\n") }
        } catch (_: Exception) {}
    }

    // -------------------------------------------------------------------------
    // Hex helpers
    // -------------------------------------------------------------------------

    fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(length / 2) { i ->
            substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // -------------------------------------------------------------------------
    // HKDF-SHA256 (RFC 5869) — no external library
    // -------------------------------------------------------------------------

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray =
        hmacSha256(salt, ikm)

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val result = ByteArray(length)
        var prev = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val block = hmacSha256(prk, prev + info + byteArrayOf(counter.toByte()))
            val copy = minOf(block.size, length - offset)
            block.copyInto(result, offset, 0, copy)
            offset += copy
            prev = block
            counter++
        }
        return result
    }

    /**
     * Derives a 32-byte AES-256 key for a conversation between [userA] and [userB].
     *
     * IKM  = pepper (NDK) + appSecret (server) + passphrase bytes
     * salt = SHA-256 of the canonical sorted username pair
     * info = (min(userA,userB) + ":" + max(userA,userB)).toByteArray()
     */
    fun deriveKey(
        passphrase: String,
        appSecret: String,
        userA: String,
        userB: String
    ): ByteArray {
        val pepper = PEPPER
        val secret = appSecret.decodeHex()
        val ikm = pepper + secret + passphrase.toByteArray(Charsets.UTF_8)

        val lo = minOf(userA, userB)
        val hi = maxOf(userA, userB)
        val pairBytes = "$lo:$hi".toByteArray(Charsets.UTF_8)

        val salt = MessageDigest.getInstance("SHA-256").digest(pairBytes)
        val info = pairBytes

        val prk = hkdfExtract(salt, ikm)
        return hkdfExpand(prk, info, 32)
    }

    // -------------------------------------------------------------------------
    // AES-256-GCM encrypt / decrypt
    // -------------------------------------------------------------------------

    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12

    /** Deterministic nonce: SHA-256(messageId ++ timestamp)[:12] */
    private fun nonce(messageId: Long, timestamp: Long): ByteArray {
        val buf = ByteBuffer.allocate(16)
        buf.putLong(messageId)
        buf.putLong(timestamp)
        return MessageDigest.getInstance("SHA-256").digest(buf.array()).copyOf(NONCE_BYTES)
    }

    /**
     * Encrypts [plaintext] and returns Base64-encoded ciphertext (includes GCM auth tag).
     * [me], [peer], [passphrase], [appSecret] are optional — used only for the debug log.
     */
    fun encrypt(
        key: ByteArray, messageId: Long, timestamp: Long, plaintext: String,
        me: String = "", peer: String = "",
        passphrase: String = "", appSecret: String = ""
    ): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce(messageId, timestamp))
        )
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val b64 = Base64.encodeToString(ct, Base64.NO_WRAP)
        val lo = minOf(me, peer); val hi = maxOf(me, peer)
        val line = "${tsFmt.format(Date())} ENCRYPT me=$me peer=$peer pair=$lo:$hi " +
                   "pass_len=${passphrase.length} pass=${passphrase.take(2)} secret=${appSecret.take(16)} " +
                   "msgId=$messageId ts=$timestamp key=${key.toHex().take(8)} ct=${b64.take(8)}"
        Log.d("Crypto", line)
        writeDebug(line)
        return b64
    }

    /**
     * Decrypts [ciphertext] (Base64). Returns null if the key is wrong or data is tampered.
     * [me], [peer], [passphrase], [appSecret] are optional — used only for the debug log.
     */
    fun decrypt(
        key: ByteArray, messageId: Long, timestamp: Long, ciphertext: String,
        me: String = "", peer: String = "",
        passphrase: String = "", appSecret: String = ""
    ): String? {
        val lo = minOf(me, peer); val hi = maxOf(me, peer)
        val prefix = "${tsFmt.format(Date())} DECRYPT me=$me peer=$peer pair=$lo:$hi " +
                     "pass_len=${passphrase.length} pass=${passphrase.take(2)} secret=${appSecret.take(16)} " +
                     "msgId=$messageId ts=$timestamp key=${key.toHex().take(8)} ct=${ciphertext.take(8)}"
        return try {
            val ct = Base64.decode(ciphertext, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce(messageId, timestamp))
            )
            cipher.doFinal(ct).toString(Charsets.UTF_8).also {
                val line = "$prefix result=OK"
                Log.d("Crypto", line)
                writeDebug(line)
            }
        } catch (e: Exception) {
            val line = "$prefix result=FAILED err=${e.javaClass.simpleName}: ${e.message}"
            Log.w("Crypto", line)
            writeDebug(line)
            null
        }
    }

    // -------------------------------------------------------------------------
    // Checksum
    // -------------------------------------------------------------------------

    /** MD5 hex of [input] — used for list integrity checks only, not for security. */
    fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // -------------------------------------------------------------------------
    // Encryption readiness + per-conversation key cache
    // -------------------------------------------------------------------------

    /** Returns true if both passphrase and appSecret are available. */
    fun isReady(context: Context): Boolean =
        Prefs.getPassphrase(context).isNotEmpty() && Prefs.getAppSecret(context).isNotEmpty()

    private val keyCache = HashMap<String, ByteArray>()

    /**
     * Returns the 32-byte AES key for the conversation between [me] and [peer].
     * Result is cached in memory; cache is cleared on process restart (passphrase change).
     */
    fun getKey(context: Context, peer: String): ByteArray {
        val me = Prefs.getUsername(context)
        val cacheKey = "${minOf(me, peer)}:${maxOf(me, peer)}"
        return keyCache.getOrPut(cacheKey) {
            val passphrase = Prefs.getPassphrase(context).trim()
            val appSecret  = Prefs.getAppSecret(context)
            val key = deriveKey(
                passphrase = passphrase,
                appSecret  = appSecret,
                userA      = me,
                userB      = peer
            )
            Log.d("CryptoKey", "pass_len=${passphrase.length} pass=${passphrase.take(2)} " +
                "secret=${appSecret.take(8)} pair=$cacheKey key=${key.toHex().take(8)}")
            key
        }
    }

    /** Call after a passphrase change to force key re-derivation. */
    fun clearKeyCache() = keyCache.clear()
}
