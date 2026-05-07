package com.fshu.next.ui.permission

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.fshu.next.R
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.databinding.ActivityPermissionSetupBinding
import com.fshu.next.ui.login.LoginActivity
import com.fshu.next.util.Prefs

class PermissionSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionSetupBinding

    private data class Step(
        val title: String,
        val description: String,
        val buttonLabel: String
    )

    private lateinit var steps: List<Step>
    private var currentStep = 0

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { advance() }

    private val audioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { advance() }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { advance() }

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { advance() }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { advance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        steps = buildList {
            add(Step(
                title = getString(R.string.permission_notifications),
                description = "Fshu needs permission to send you notifications for incoming messages and calls.",
                buttonLabel = "Allow Notifications"
            ))
            add(Step(
                title = getString(R.string.permission_microphone),
                description = "Fshu needs access to your microphone to make and receive voice calls.",
                buttonLabel = "Allow Microphone"
            ))
            add(Step(
                title = getString(R.string.permission_camera),
                description = "Fshu needs access to your camera to make and receive video calls.",
                buttonLabel = "Allow Camera"
            ))
            add(Step(
                title = getString(R.string.permission_location),
                description = "Allows Fshu to share your GPS position when you or someone requests it. This is entirely optional — you can skip it now and enable location sharing later in Settings.",
                buttonLabel = "Allow Location"
            ))
            add(Step(
                title = getString(R.string.permission_auto_location),
                description = "Should Fshu automatically reply with your GPS position when someone requests your location? You can change this anytime in Settings.",
                buttonLabel = "Enable auto-sharing"
            ))
            add(Step(
                title = getString(R.string.permission_push),
                description = "Fshu can use Google Firebase to wake the app when a message or call arrives while Fshu is not running. This uses Google's servers. Skip if you prefer maximum privacy — the app will still work without it.",
                buttonLabel = "Enable push wake-up"
            ))
            add(Step(
                title = getString(R.string.permission_battery),
                description = "To keep your connection alive and receive calls in the background, please disable battery optimization for Fshu.",
                buttonLabel = "Open Settings"
            ))
            add(Step(
                title = getString(R.string.permission_overlay),
                description = "Fshu needs to show call screens when your display is off or another app is open. Please grant the \"Display over other apps\" permission.",
                buttonLabel = "Open Settings"
            ))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(Step(
                    title = getString(R.string.permission_fullscreen),
                    description = "Fshu needs permission to display incoming calls on your lock screen. Please grant the \"Display full-screen intent\" permission.",
                    buttonLabel = "Open Settings"
                ))
            }
        }

        binding.btnAllow.setOnClickListener { handleStep() }
        binding.btnSkip.setOnClickListener { advance() }

        showStep(currentStep)
    }

    private fun showStep(index: Int) {
        val step = steps[index]
        binding.tvStepIndicator.text = getString(R.string.permission_step_indicator, index + 1, steps.size)
        binding.tvTitle.text = step.title
        binding.tvDescription.text = step.description
        binding.btnAllow.text = step.buttonLabel
    }

    private fun handleStep() {
        when (currentStep) {
            0 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    advance()
                }
            }
            1 -> audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            2 -> cameraLauncher.launch(Manifest.permission.CAMERA)
            3 -> locationLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
            4 -> {
                // "Enable auto-sharing" button — set pref and advance
                Prefs.setLocationSharingEnabled(this, true)
                advance()
            }
            5 -> {
                Prefs.setFcmEnabled(this, true)
                val token = Prefs.getFcmToken(this)
                if (token.isNotEmpty() && WebSocketClient.isConnected) {
                    WebSocketClient.send(mapOf("type" to "fcm-token", "token" to token))
                }
                advance()
            }
            6 -> {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                settingsLauncher.launch(intent)
            }
            7 -> {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                settingsLauncher.launch(intent)
            }
            8 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val nm = getSystemService(NotificationManager::class.java)
                    if (nm.canUseFullScreenIntent()) {
                        advance()
                    } else {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        settingsLauncher.launch(intent)
                    }
                } else {
                    advance()
                }
            }
        }
    }

    private fun advance() {
        currentStep++
        if (currentStep >= steps.size) {
            Prefs.setPermissionsSetupDone(this, true)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        } else {
            showStep(currentStep)
        }
    }
}
