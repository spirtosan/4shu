package com.fshu.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.fshu.util.Prefs

object AppLockManager {

    private var lockedAt: Long = 0L
    private const val LOCK_TIMEOUT_MS = 60_000L // 1 minute background before locking

    fun onAppBackground() {
        lockedAt = System.currentTimeMillis()
    }

    fun shouldLock(ctx: Context): Boolean {
        if (!Prefs.getAppLockEnabled(ctx)) return false
        if (lockedAt == 0L) return true
        return System.currentTimeMillis() - lockedAt > LOCK_TIMEOUT_MS
    }

    fun isBiometricAvailable(ctx: Context): Boolean {
        val bm = BiometricManager.from(ctx)
        return bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun showPrompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailed: () -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                lockedAt = 0L
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailed()
            }
            override fun onAuthenticationFailed() {}
        }
        val prompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("4shu")
            .setSubtitle("Unlock to continue")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }
}
