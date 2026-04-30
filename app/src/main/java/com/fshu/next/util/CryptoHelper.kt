package com.fshu.next.util

import android.content.Context
import android.util.Log
import com.fshu.next.util.EcdhHelper.fromHex
import com.fshu.next.util.EcdhHelper.toHex

/**
 * Thin adapter that forwards to EcdhHelper.
 * External API is kept stable so all existing call sites compile without changes.
 * The passphrase / appSecret parameters are accepted but ignored.
 */
object CryptoHelper {
    private const val TAG = "CryptoHelper"

    // In-memory cache: "lo:hi" → 32-byte AES-256 conversation key.
    // Populated by cachePeerKey() called from FshuService when a public-key-response arrives
    // or when peer keys are loaded from the DB after connect.
    private val keyCache = HashMap<String, ByteArray>()

    // -------------------------------------------------------------------------
    // Key cache management  (called from FshuService on IO thread)
    // -------------------------------------------------------------------------

    /** Store a peer's derived conversation key in the in-memory cache. */
    fun cachePeerKey(context: Context, peer: String, peerPubHex: String) {
        val me       = Prefs.getUsername(context)
        val cacheKey = cacheKey(me, peer)
        keyCache[cacheKey] = EcdhHelper.deriveConversationKey(
            Prefs.getEcPrivateKey(context), peerPubHex, me, peer
        )
        Log.d(TAG, "key cached for $peer")
    }

    /**
     * Returns the AES-256 conversation key for [peer], or null if the peer's
     * public key has not been received yet. Never blocks — returns from cache only.
     */
    fun getKey(context: Context, peer: String): ByteArray? =
        keyCache[cacheKey(Prefs.getUsername(context), peer)]

    /** True once our own EC keypair has been generated (always true after first launch). */
    fun isReady(context: Context): Boolean =
        Prefs.getEcPrivateKey(context).isNotEmpty()

    /** Clear all cached keys (e.g. on logout). */
    fun clearKeyCache() = keyCache.clear()

    // -------------------------------------------------------------------------
    // Encrypt / Decrypt  — timestamp param kept for call-site compatibility, ignored
    // -------------------------------------------------------------------------

    fun encrypt(
        key: ByteArray,
        messageId: Long,
        @Suppress("UNUSED_PARAMETER") timestamp: Long,
        plaintext: String,
        @Suppress("UNUSED_PARAMETER") me: String = "",
        @Suppress("UNUSED_PARAMETER") peer: String = "",
        @Suppress("UNUSED_PARAMETER") passphrase: String = "",
        @Suppress("UNUSED_PARAMETER") appSecret: String = ""
    ): String = EcdhHelper.encrypt(key, messageId, plaintext)

    fun decrypt(
        key: ByteArray,
        messageId: Long,
        @Suppress("UNUSED_PARAMETER") timestamp: Long,
        ciphertext: String,
        @Suppress("UNUSED_PARAMETER") me: String = "",
        @Suppress("UNUSED_PARAMETER") peer: String = "",
        @Suppress("UNUSED_PARAMETER") passphrase: String = "",
        @Suppress("UNUSED_PARAMETER") appSecret: String = ""
    ): String? = EcdhHelper.decrypt(key, messageId, ciphertext)

    // -------------------------------------------------------------------------
    // Hex helpers  (re-exported so existing callers of CryptoHelper still compile)
    // -------------------------------------------------------------------------

    fun String.decodeHex(): ByteArray = with(EcdhHelper) { this@decodeHex.fromHex() }
    fun ByteArray.toHex(): String     = with(EcdhHelper) { this@toHex.toHex() }

    /** MD5 hex — for list integrity checks only, not security. */
    fun md5(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun cacheKey(a: String, b: String) = "${minOf(a, b)}:${maxOf(a, b)}"

    // Legacy no-op kept in case any code still calls initDebugLog
    fun initDebugLog(@Suppress("UNUSED_PARAMETER") context: Context) {}
}
