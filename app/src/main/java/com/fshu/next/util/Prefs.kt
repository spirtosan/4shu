package com.fshu.next.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

object Prefs {
    private const val NAME = "fshu_prefs"
    private const val SECURE_NAME = "fshu_secure_prefs"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_PERMISSIONS_SETUP_DONE = "permissions_setup_done"
    private const val KEY_APP_SECRET = "app_secret"
    private const val KEY_PASSPHRASE = "passphrase"
    private const val KEY_PASSPHRASE_HINT = "passphrase_hint"
    private const val KEY_IS_ADMIN = "is_admin"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_EC_PRIVATE_KEY = "ec_private_key"
    private const val KEY_EC_PUBLIC_KEY  = "ec_public_key"

    fun getUsername(ctx: Context) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_USERNAME, "") ?: ""

    fun setUsername(ctx: Context, value: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(KEY_USERNAME, value).apply()

    fun getServerUrl(ctx: Context) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_SERVER_URL, "") ?: ""

    fun setServerUrl(ctx: Context, value: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(KEY_SERVER_URL, value).apply()

    fun isPermissionsSetupDone(ctx: Context) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEY_PERMISSIONS_SETUP_DONE, false)

    fun setPermissionsSetupDone(ctx: Context, value: Boolean) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_PERMISSIONS_SETUP_DONE, value).apply()

    // Background settings — index -1 = default, 0-5 = gradient, 6 = custom photo
    // Chat backgrounds are stored per-peer using the username as part of the key.
    private const val KEY_MAIN_BG_INDEX = "main_bg_index"
    private const val KEY_MAIN_BG_URI   = "main_bg_uri"

    const val BG_DEFAULT = -1
    const val BG_CUSTOM  = 6

    fun getChatBgIndex(ctx: Context, peer: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt("chat_bg_index_$peer", BG_DEFAULT)

    fun setChatBgIndex(ctx: Context, peer: String, value: Int) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt("chat_bg_index_$peer", value).apply()

    fun getChatBgUri(ctx: Context, peer: String): String? =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("chat_bg_uri_$peer", null)

    fun setChatBgUri(ctx: Context, peer: String, value: String?) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString("chat_bg_uri_$peer", value).apply()

    fun getMainBgIndex(ctx: Context) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt(KEY_MAIN_BG_INDEX, BG_DEFAULT)

    fun setMainBgIndex(ctx: Context, value: Int) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt(KEY_MAIN_BG_INDEX, value).apply()

    fun getMainBgUri(ctx: Context): String? =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_MAIN_BG_URI, null)

    fun setMainBgUri(ctx: Context, value: String?) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(KEY_MAIN_BG_URI, value).apply()

    fun getPassword(ctx: Context): String =
        getSecurePrefs(ctx).getString(KEY_PASSWORD, "") ?: ""

    fun setPassword(ctx: Context, value: String) =
        getSecurePrefs(ctx).edit().putString(KEY_PASSWORD, value).apply()

    /** Server-issued per-app secret hex string, stored encrypted. */
    fun getAppSecret(ctx: Context): String =
        getSecurePrefs(ctx).getString(KEY_APP_SECRET, "") ?: ""

    fun setAppSecret(ctx: Context, value: String) =
        getSecurePrefs(ctx).edit().putString(KEY_APP_SECRET, value).apply()

    /** User's encryption passphrase, stored encrypted. */
    fun getPassphrase(ctx: Context): String =
        getSecurePrefs(ctx).getString(KEY_PASSPHRASE, "") ?: ""

    fun setPassphrase(ctx: Context, value: String) =
        getSecurePrefs(ctx).edit().putString(KEY_PASSPHRASE, value).apply()

    /** Auto-respond to location requests (legacy global toggle — kept for backward compat). */
    fun getLocationSharingEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean("location_sharing_enabled", false)

    fun setLocationSharingEnabled(ctx: Context, value: Boolean) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean("location_sharing_enabled", value).apply()

    /** Per-peer auto-share: set of peer usernames for whom location is auto-shared. */
    fun getAutoLocationPeers(ctx: Context): Set<String> =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getStringSet("auto_location_peers", emptySet()) ?: emptySet()

    fun setAutoLocationPeers(ctx: Context, peers: Set<String>) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putStringSet("auto_location_peers", peers).apply()

    fun isAutoLocationEnabled(ctx: Context, peer: String): Boolean =
        getAutoLocationPeers(ctx).contains(peer)

    /** Cached user list — JSON array of {username,online}. Loaded on start before server update. */
    fun getCachedUsers(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("cached_users", "[]") ?: "[]"

    fun setCachedUsers(ctx: Context, json: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString("cached_users", json).apply()

    /** Optional passphrase hint, stored in plain prefs (not secret). */
    fun getPassphraseHint(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_PASSPHRASE_HINT, "") ?: ""

    fun setPassphraseHint(ctx: Context, value: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(KEY_PASSPHRASE_HINT, value).apply()

    /** Device UUID — generated once on first launch, stored encrypted. */
    fun getDeviceId(ctx: Context): String =
        getSecurePrefs(ctx).getString(KEY_DEVICE_ID, "") ?: ""

    fun setDeviceId(ctx: Context, value: String) =
        getSecurePrefs(ctx).edit().putString(KEY_DEVICE_ID, value).apply()

    /** Human-readable device name. Defaults to Build.MODEL; user can rename in Settings. */
    fun getDeviceName(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_DEVICE_NAME, "") ?: ""

    fun setDeviceName(ctx: Context, value: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(KEY_DEVICE_NAME, value).apply()

    /** X25519 private key (hex, 32 bytes). Stored encrypted. Never leaves the device. */
    fun getEcPrivateKey(ctx: Context): String =
        getSecurePrefs(ctx).getString(KEY_EC_PRIVATE_KEY, "") ?: ""

    fun setEcPrivateKey(ctx: Context, value: String) =
        getSecurePrefs(ctx).edit().putString(KEY_EC_PRIVATE_KEY, value).apply()

    /** X25519 public key (hex, 32 bytes). Stored in plain prefs — not secret. */
    fun getEcPublicKey(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_EC_PUBLIC_KEY, "") ?: ""

    fun setEcPublicKey(ctx: Context, value: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(KEY_EC_PUBLIC_KEY, value).apply()

    /** Peer's X25519 public key (hex). Cached locally so decryption works after cache warm. */
    fun getPeerPublicKey(ctx: Context, peer: String): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("peer_pub_$peer", "") ?: ""

    fun setPeerPublicKey(ctx: Context, peer: String, key: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString("peer_pub_$peer", key).apply()

    fun clearPeerPublicKey(ctx: Context, peer: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove("peer_pub_$peer").apply()

    fun isAdmin(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEY_IS_ADMIN, false)

    fun setIsAdmin(ctx: Context, value: Boolean) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_IS_ADMIN, value).apply()

    fun getUserOrder(ctx: Context): List<String> {
        val json = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString("user_order", "[]") ?: "[]"
        return try {
            com.google.gson.JsonParser.parseString(json).asJsonArray
                .map { it.asString }
        } catch (e: Exception) { emptyList() }
    }

    fun setUserOrder(ctx: Context, order: List<String>) {
        val arr = com.google.gson.JsonArray()
        order.forEach { arr.add(it) }
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString("user_order", arr.toString()).apply()
    }

    fun getMyNickname(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString("my_nickname", "") ?: ""

    fun setMyNickname(ctx: Context, value: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString("my_nickname", value).apply()

    fun getFcmEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean("fcm_enabled", false)

    fun setFcmEnabled(ctx: Context, value: Boolean) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("fcm_enabled", value).apply()

    fun getFcmToken(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString("fcm_token", "") ?: ""

    fun setFcmToken(ctx: Context, value: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString("fcm_token", value).apply()

    fun getAppLockEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean("app_lock_enabled", false)

    fun setAppLockEnabled(ctx: Context, enabled: Boolean) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("app_lock_enabled", enabled).apply()

    fun getAppLockTimeoutMs(ctx: Context): Long =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getLong("app_lock_timeout_ms", 60_000L)

    fun setAppLockTimeoutMs(ctx: Context, ms: Long) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putLong("app_lock_timeout_ms", ms).apply()

    fun getSessionToken(ctx: Context): String =
        getSecurePrefs(ctx).getString("session_token", "") ?: ""

    fun setSessionToken(ctx: Context, value: String) =
        getSecurePrefs(ctx).edit().putString("session_token", value).apply()

    fun getTurnUsername(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString("turn_username", "") ?: ""

    fun setTurnUsername(ctx: Context, value: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString("turn_username", value).apply()

    fun getTurnPassword(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString("turn_password", "") ?: ""

    fun setTurnPassword(ctx: Context, value: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString("turn_password", value).apply()

    fun getMyPrivateKeyBytes(ctx: Context): ByteArray =
        with(EcdhHelper) { getEcPrivateKey(ctx).fromHex() }

    fun getMyPublicKeyHex(ctx: Context): String = getEcPublicKey(ctx)

    fun getContactNickname(ctx: Context, username: String): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("contact_nick_$username", "") ?: ""

    fun setContactNickname(ctx: Context, username: String, value: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString("contact_nick_$username", value).apply()

    fun getDraft(ctx: Context, chatId: String): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("draft_$chatId", "") ?: ""

    fun setDraft(ctx: Context, chatId: String, text: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString("draft_$chatId", text).apply()

    fun clearDraft(ctx: Context, chatId: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove("draft_$chatId").apply()

    fun getFavorites(ctx: Context): Set<String> =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getStringSet("favorites", emptySet()) ?: emptySet()

    fun setFavorites(ctx: Context, favs: Set<String>) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putStringSet("favorites", favs).apply()

    fun getMyEmail(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("my_email", "") ?: ""

    fun setMyEmail(ctx: Context, value: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString("my_email", value).apply()

    fun getMyPhone(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("my_phone", "") ?: ""

    fun setMyPhone(ctx: Context, value: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString("my_phone", value).apply()

    fun getMyBio(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("my_bio", "") ?: ""

    fun setMyBio(ctx: Context, value: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString("my_bio", value).apply()

    fun getPrivacyDiscoverable(ctx: Context): Int =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt("privacy_discoverable", 1)

    fun setPrivacyDiscoverable(ctx: Context, value: Int) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt("privacy_discoverable", value).apply()

    fun getPrivacyShowAvatar(ctx: Context): Int =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt("privacy_show_avatar", 1)

    fun setPrivacyShowAvatar(ctx: Context, value: Int) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt("privacy_show_avatar", value).apply()

    fun getPrivacyShowNickname(ctx: Context): Int =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt("privacy_show_nickname", 1)

    fun setPrivacyShowNickname(ctx: Context, value: Int) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt("privacy_show_nickname", value).apply()

    fun getPrivacyEmailSearchable(ctx: Context): Int =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt("privacy_email_searchable", 0)

    fun setPrivacyEmailSearchable(ctx: Context, value: Int) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt("privacy_email_searchable", value).apply()

    fun getPrivacyPhoneSearchable(ctx: Context): Int =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt("privacy_phone_searchable", 0)

    fun setPrivacyPhoneSearchable(ctx: Context, value: Int) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt("privacy_phone_searchable", value).apply()

    fun getPrivacyHidePresence(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean("privacy_hide_presence", false)

    fun setPrivacyHidePresence(ctx: Context, value: Boolean) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean("privacy_hide_presence", value).apply()

    fun getReadReceiptsEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean("read_receipts_enabled", true)

    fun setReadReceiptsEnabled(ctx: Context, value: Boolean) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean("read_receipts_enabled", value).apply()

    fun getDisplayName(ctx: Context, username: String): String {
        val contactNick = getContactNickname(ctx, username)
        if (contactNick.isNotEmpty()) return contactNick
        return try {
            com.google.gson.JsonParser.parseString(getCachedUsers(ctx)).asJsonArray
                .firstOrNull { it.asJsonObject.get("username")?.asString == username }
                ?.asJsonObject?.get("nickname")?.takeIf { !it.isJsonNull }?.asString
                ?: username
        } catch (_: Exception) { username }
    }

    // T13 Block D — Trail enable state, guardian picker (local-only, Phase 2/3 wire
    // not built yet), and restart-count health surfaced on the status card (§6.6).
    fun isTrailEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean("trail_enabled", false)

    fun setTrailEnabled(ctx: Context, value: Boolean) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean("trail_enabled", value).apply()

    /** Epoch ms Trail was last enabled; 0 = never. Drives the status card's "collecting since". */
    fun getTrailEnabledAt(ctx: Context): Long =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getLong("trail_enabled_at", 0L)

    fun setTrailEnabledAt(ctx: Context, value: Long) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putLong("trail_enabled_at", value).apply()

    /** OS-triggered TrailService restarts (svc_restart) since Trail was last (re-)enabled. */
    fun getTrailRestartCount(ctx: Context): Int =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt("trail_restart_count", 0)

    fun incrementTrailRestartCount(ctx: Context) {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        p.edit().putInt("trail_restart_count", p.getInt("trail_restart_count", 0) + 1).apply()
    }

    fun resetTrailRestartCount(ctx: Context) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt("trail_restart_count", 0).apply()

    /** Guardian usernames picked locally (§6.2). Grant/accept wire protocol is Phase 2/3 —
     *  this is purely a local list until then, cap enforced by the picker UI, not here. */
    fun getTrailGuardians(ctx: Context): Set<String> =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getStringSet("trail_guardians", emptySet()) ?: emptySet()

    fun setTrailGuardians(ctx: Context, guardians: Set<String>) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putStringSet("trail_guardians", guardians).apply()

    /** Chunk 2 — "wards": people who granted ME guardianship of their trail.
     *  PENDING = granted, awaiting my accept; ACCEPTED = I sent trail-accept and may fetch.
     *  Maintained from incoming trail-guardian-changed pushes (see FshuService). */
    fun getTrailWardsPending(ctx: Context): Set<String> =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getStringSet("trail_wards_pending", emptySet()) ?: emptySet()

    fun setTrailWardsPending(ctx: Context, wards: Set<String>) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putStringSet("trail_wards_pending", wards).apply()

    fun getTrailWardsAccepted(ctx: Context): Set<String> =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getStringSet("trail_wards_accepted", emptySet()) ?: emptySet()

    fun setTrailWardsAccepted(ctx: Context, wards: Set<String>) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putStringSet("trail_wards_accepted", wards).apply()

    /** Chunk 4 — tracked-side access log (who fetched my trail), a JSON array string. */
    fun getTrailAccessLogJson(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("trail_access_log", "[]") ?: "[]"

    fun setTrailAccessLogJson(ctx: Context, json: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString("trail_access_log", json).apply()

    /** T13 Block I — admin trail recipients (id + hex pub) as a JSON string, from auth-ok.
     *  Persisted so the uploader can still fan out to admins after a process-death restart. */
    fun getTrailAdmins(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("trail_admins", "") ?: ""

    fun setTrailAdmins(ctx: Context, json: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString("trail_admins", json).apply()

    /** T13 Block H — last local frozen-clock retention purge (epoch ms); throttles to once/day. */
    fun getTrailLastPurgeTs(ctx: Context): Long =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getLong("trail_last_purge_ts", 0L)

    fun setTrailLastPurgeTs(ctx: Context, value: Long) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putLong("trail_last_purge_ts", value).apply()

    private fun getSecurePrefs(ctx: Context): SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                SECURE_NAME,
                masterKeyAlias,
                ctx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // If the Keystore entry is corrupted (e.g. after a factory reset without
            // clearing app data), wipe the file and recreate from scratch.
            ctx.deleteSharedPreferences(SECURE_NAME)
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                SECURE_NAME,
                masterKeyAlias,
                ctx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}
