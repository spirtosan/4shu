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
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_SERVER_URL, "wss://shumkov.eu/fshu5/") ?: "wss://shumkov.eu/fshu5/"

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

    /** Auto-respond to location requests. */
    fun getLocationSharingEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean("location_sharing_enabled", false)

    fun setLocationSharingEnabled(ctx: Context, value: Boolean) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean("location_sharing_enabled", value).apply()

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

    fun getContactNickname(ctx: Context, username: String): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("contact_nick_$username", "") ?: ""

    fun setContactNickname(ctx: Context, username: String, value: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString("contact_nick_$username", value).apply()

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
