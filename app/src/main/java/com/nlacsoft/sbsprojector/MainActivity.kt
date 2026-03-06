package com.nlacsoft.sbsprojector

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nlacsoft.sbsprojector.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val prefs by lazy { getSharedPreferences("sbs_prefs", MODE_PRIVATE) }

    // Launcher for MediaProjection consent dialog
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startSbsService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Launcher for overlay permission settings
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            requestMediaProjection()
        } else {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
        }
    }

    // Launcher for notification permission (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Continue regardless — notification permission is optional
        checkOverlayAndProceed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        binding.btnStartSbs.setOnClickListener { onStartClicked() }
        binding.tvAccessibilityStatus.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnHelp.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("How to use")
                .setMessage(getString(R.string.instructions))
                .setPositiveButton("OK", null)
                .show()
        }

        binding.seekShrink.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val v = progress / 100f
                SbsOverlayService.shrink = v
                binding.tvShrinkValue.text = "%.2f".format(v)
                if (fromUser) prefs.edit().putInt("shrink_progress", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        binding.seekCloseness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val v = 0.3f + progress / 100f
                SbsOverlayService.closeness = v
                binding.tvClosenessValue.text = "%.2f".format(v)
                if (fromUser) prefs.edit().putInt("closeness_progress", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // Restore persisted slider values — triggers onProgressChanged to sync service + labels
        binding.seekShrink.progress = prefs.getInt("shrink_progress", 65)
        binding.seekCloseness.progress = prefs.getInt("closeness_progress", 40)

        updateButtonState()
    }

    override fun onResume() {
        super.onResume()
        updateButtonState()
    }

    private fun onStartClicked() {
        // Step 1: accessibility service — mandatory for the overlay window type
        if (!isAccessibilityServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Accessibility permission required")
                .setMessage("SBS Projector needs the Accessibility permission to display the stereoscopic overlay. Please enable \"SBS Projector\" in Settings → Accessibility.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        // Step 2: notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        checkOverlayAndProceed()
    }

    private fun checkOverlayAndProceed() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }
        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startSbsService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, SbsOverlayService::class.java).apply {
            putExtra(SbsOverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(SbsOverlayService.EXTRA_PROJECTION_DATA, data)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        finish()  // Remove MainActivity from the window stack so the projected app becomes the active window for gesture injection
    }

    private fun updateButtonState(running: Boolean = SbsOverlayService.isRunning) {
        binding.btnStartSbs.isEnabled = !running

        val a11yEnabled = isAccessibilityServiceEnabled()
        binding.tvAccessibilityStatus.text = if (a11yEnabled)
            "Accessibility: Enabled"
        else
            "Accessibility permission required — tap to enable"
        binding.tvAccessibilityStatus.setTextColor(
            if (a11yEnabled) Color.parseColor("#4CAF50") else Color.parseColor("#FF9800")
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }
}
