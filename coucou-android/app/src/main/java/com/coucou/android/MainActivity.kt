package com.coucou.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.coucou.android.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateUiState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun setupListeners() {
        binding.btnGrantPermission.setOnClickListener {
            requestOverlayPermission()
        }

        binding.btnStartOverlay.setOnClickListener {
            if (hasOverlayPermission()) {
                startOverlayService()
            } else {
                requestOverlayPermission()
            }
        }

        binding.btnStopOverlay.setOnClickListener {
            stopOverlayService()
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            overlayPermissionLauncher.launch(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            startActivity(fallbackIntent)
        }
    }

    private fun startOverlayService() {
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        Toast.makeText(this, R.string.start_overlay, Toast.LENGTH_SHORT).show()
        updateUiState()
    }

    private fun stopOverlayService() {
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        startService(serviceIntent)
        Toast.makeText(this, R.string.stop_overlay, Toast.LENGTH_SHORT).show()
        updateUiState()
    }

    private fun updateUiState() {
        val granted = hasOverlayPermission()
        if (granted) {
            binding.permissionStatusText.setText(R.string.permission_granted)
            binding.btnGrantPermission.visibility = View.GONE
            binding.btnStartOverlay.isEnabled = true
        } else {
            binding.permissionStatusText.setText(R.string.permission_required)
            binding.btnGrantPermission.visibility = View.VISIBLE
            binding.btnStartOverlay.isEnabled = false
        }

        if (OverlayService.isRunning) {
            binding.serviceStatusText.setText(R.string.overlay_status_running)
            binding.btnStopOverlay.isEnabled = true
        } else {
            binding.serviceStatusText.setText(R.string.overlay_status_stopped)
            binding.btnStopOverlay.isEnabled = false
        }
    }
}
