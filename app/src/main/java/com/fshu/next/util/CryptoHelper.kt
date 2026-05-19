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

    /**
     * Encrypt [plaintext] for [peer], deriving the conversation key from [peerPubHex] if not
     * already cached. Returns ciphertext, or [plaintext] unchanged if key derivation fails.
     */
    fun encryptForPeer(ctx: Context, peer: String, peerPubHex: String, messageId: Long, plaintext: String): String {
        if (getKey(ctx, peer) == null) cachePeerKey(ctx, peer, peerPubHex)
        val key = getKey(ctx, peer) ?: return plaintext
        return EcdhHelper.encrypt(key, messageId, plaintext)
    }

    /**
     * Decrypt [ciphertext] from [peer], deriving the conversation key from [peerPubHex] if not
     * already cached. Returns null if decryption fails (caller should fall back to raw).
     */
    fun decryptFromPeer(ctx: Context, peer: String, peerPubHex: String, messageId: Long, ciphertext: String): String? {
        if (getKey(ctx, peer) == null) cachePeerKey(ctx, peer, peerPubHex)
        val key = getKey(ctx, peer) ?: return null
        return EcdhHelper.decrypt(key, messageId, ciphertext)
    }

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

    fun encryptFileForPeer(ctx: Context, peer: String, peerPubHex: String, plaintext: ByteArray): Pair<ByteArray, ByteArray>? {
        if (getKey(ctx, peer) == null) cachePeerKey(ctx, peer, peerPubHex)
        val convKey = getKey(ctx, peer) ?: return null
        val fileKey = EcdhHelper.deriveFileKey(convKey)
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val encrypted = EcdhHelper.encryptBytes(fileKey, nonce, plaintext)
        return Pair(encrypted, nonce)
    }

    fun decryptFileFromPeer(ctx: Context, peer: String, nonceHex: String, ciphertext: ByteArray): ByteArray? {
        val convKey = getKey(ctx, peer) ?: run {
            Log.w(TAG, "No key for $peer, can't decrypt file")
            return null
        }
        val fileKey = EcdhHelper.deriveFileKey(convKey)
        val nonce = with(EcdhHelper) { nonceHex.fromHex() }
        return EcdhHelper.decryptBytes(fileKey, nonce, ciphertext)
    }

    fun bytesToHex(bytes: ByteArray): String = with(EcdhHelper) { bytes.toHex() }

    // -------------------------------------------------------------------------
    // Group crypto wrappers
    // -------------------------------------------------------------------------

    fun generateGroupKey(): ByteArray = EcdhHelper.generateGroupKey()

    fun encryptGroupKeyForMember(groupKey: ByteArray, memberPubHex: String, myPrivHex: String): String =
        EcdhHelper.encryptGroupKey(groupKey, memberPubHex, myPrivHex)

    fun decryptGroupKey(encryptedHex: String, senderPubHex: String, myPrivHex: String): ByteArray? =
        EcdhHelper.decryptGroupKey(encryptedHex, senderPubHex, myPrivHex)

    fun encryptGroupMessage(groupKey: ByteArray, plaintext: String): Pair<String, String> =
        EcdhHelper.encryptGroupMessage(groupKey, plaintext)

    fun decryptGroupMessage(groupKey: ByteArray, ciphertext: String): String? =
        EcdhHelper.decryptGroupMessage(groupKey, ciphertext)

    fun encryptGroupFile(groupKey: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val encrypted = EcdhHelper.encryptBytes(groupKey, nonce, plaintext)
        return Pair(encrypted, nonce)
    }

    fun decryptGroupFile(groupKey: ByteArray, nonceHex: String, ciphertext: ByteArray): ByteArray? {
        val nonce = with(EcdhHelper) { nonceHex.fromHex() }
        return EcdhHelper.decryptBytes(groupKey, nonce, ciphertext)
    }

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
