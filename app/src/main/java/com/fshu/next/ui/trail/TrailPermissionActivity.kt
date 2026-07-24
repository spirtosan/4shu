package com.fshu.next.ui.trail

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fshu.next.R
import com.fshu.next.databinding.ActivityTrailPermissionStepBinding

/**
 * SPEC_T13.md §3.6 staged permission flow (Block D, amended Block B.1.4), scoped to
 * what Trail itself needs: fine location (mandatory) -> background location "Allow all
 * the time" (mandatory as of B.1.4, guided to Settings) -> battery-optimization
 * exemption (skippable, guided to Settings). Mirrors [com.fshu.next.ui.permission.
 * PermissionSetupActivity]'s step-list/step-indicator style, on its own layout/activity
 * rather than reusing that one directly — this flow is re-enterable from Trail settings
 * any time, not a one-shot first-launch screen.
 *
 * Result: RESULT_OK only if both ACCESS_FINE_LOCATION and (API 30+)
 * ACCESS_BACKGROUND_LOCATION end up granted; RESULT_CANCELED otherwise. B.1.4 made
 * background location mandatory — a real-device outage (TrailService died silently for
 * 7 days) showed a foreground-only grant leaves a restarted service running on
 * borrowed reliability, which the honest-status card can warn about but not fix. The
 * battery-optimization exemption stays a best-effort ask: skipping it still lets Trail
 * start, just less reliably in the background.
 */
class TrailPermissionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrailPermissionStepBinding

    private enum class StepKind { LOCATION, BACKGROUND_LOCATION, BATTERY }
    private data class Step(
        val kind: StepKind,
        val title: String,
        val description: String,
        val buttonLabel: String,
        val skippable: Boolean
    )

    private lateinit var steps: List<Step>
    private var currentStep = 0

    private val fineLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            advance()
        } else {
            Toast.makeText(this, getString(R.string.toast_trail_location_required), Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // B.1.4: background location is now mandatory — unlike the battery-exemption
        // step (still best-effort, always advances), verify the grant actually landed
        // before moving on. Settings doesn't return a result code we can trust here
        // (ACTION_APPLICATION_DETAILS_SETTINGS always finishes with RESULT_CANCELED
        // regardless of what the user did inside it), so check the permission directly.
        if (steps[currentStep].kind == StepKind.BACKGROUND_LOCATION && !isBackgroundLocationGranted()) {
            Toast.makeText(this, getString(R.string.toast_trail_background_required), Toast.LENGTH_LONG).show()
            showStep(currentStep)
        } else {
            advance()
        }
    }

    private fun isBackgroundLocationGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrailPermissionStepBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val backgroundGranted = isBackgroundLocationGranted()
        val batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        steps = buildList {
            if (!fineGranted) add(Step(
                kind = StepKind.LOCATION,
                title = getString(R.string.trail_permission_location_title),
                description = getString(R.string.trail_permission_location_desc),
                buttonLabel = getString(R.string.trail_permission_location_button),
                skippable = false
            ))
            if (!backgroundGranted) add(Step(
                kind = StepKind.BACKGROUND_LOCATION,
                title = getString(R.string.trail_permission_background_title),
                description = getString(R.string.trail_permission_background_desc),
                buttonLabel = getString(R.string.btn_open_settings),
                skippable = false // B.1.4: was skippable — see class doc
            ))
            if (!batteryExempt) add(Step(
                kind = StepKind.BATTERY,
                title = getString(R.string.trail_permission_battery_title),
                description = getString(R.string.trail_permission_battery_desc),
                buttonLabel = getString(R.string.btn_open_settings),
                skippable = true
            ))
        }

        if (steps.isEmpty()) {
            finishAfterAllSteps()
            return
        }

        binding.btnAllow.setOnClickListener { handleStep() }
        binding.btnSkip.setOnClickListener { advance() }

        showStep(0)
    }

    private fun showStep(index: Int) {
        val step = steps[index]
        binding.tvStepIndicator.text = getString(R.string.permission_step_indicator, index + 1, steps.size)
        binding.tvTitle.text = step.title
        binding.tvDescription.text = step.description
        binding.btnAllow.text = step.buttonLabel
        binding.btnSkip.visibility = if (step.skippable) View.VISIBLE else View.GONE
    }

    private fun handleStep() {
        when (steps[currentStep].kind) {
            StepKind.LOCATION -> fineLocationLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
            StepKind.BACKGROUND_LOCATION -> settingsLauncher.launch(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
            StepKind.BATTERY -> settingsLauncher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }
    }

    private fun advance() {
        currentStep++
        if (currentStep >= steps.size) finishAfterAllSteps() else showStep(currentStep)
    }

    private fun finishAfterAllSteps() {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        // B.1.4: both mandatory now — see class doc.
        setResult(if (fineGranted && isBackgroundLocationGranted()) RESULT_OK else RESULT_CANCELED)
        finish()
    }
}
