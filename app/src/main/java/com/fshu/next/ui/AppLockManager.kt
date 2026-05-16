package com.fshu.next.ui

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.fshu.next.R
import com.fshu.next.util.Prefs

object AppLockManager {
    private var backgroundedAt: Long = 0L
    private var isLocked: Boolean = true  // starts locked until first successful auth

    fun onAppBackground() {
        backgroundedAt = System.currentTimeMillis()
    }

    fun shouldLock(ctx: Context): Boolean {
        if (!Prefs.getAppLockEnabled(ctx)) return false
        if (isLocked) return true
        if (backgroundedAt <= 0L) return false
        val timeoutMs = Prefs.getAppLockTimeoutMs(ctx)
        return System.currentTimeMillis() - backgroundedAt > timeoutMs
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
                isLocked = false
                backgroundedAt = 0L
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                    onFailed()
                }
            }
            override fun onAuthenticationFailed() {}
        }
        val prompt = BiometricPrompt(activity, executor, callback)
        val promptInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.app_lock_title))
                .setSubtitle(activity.getString(R.string.label_unlock_to_continue))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
        } else {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.app_lock_title))
                .setSubtitle(activity.getString(R.string.label_unlock_to_continue))
                .setNegativeButtonText(activity.getString(R.string.btn_cancel))
                .build()
        }
        prompt.authenticate(promptInfo)
    }
}
