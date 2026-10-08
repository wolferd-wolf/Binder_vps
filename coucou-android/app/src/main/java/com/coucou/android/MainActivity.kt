package com.coucou.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AppCompatActivity
import com.coucou.android.databinding.ActivityMainBinding
import com.coucou.android.TaskStore

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var taskStore: TaskStore

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        updateUiState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        taskStore = TaskStore.create(this)

        binding.buildInfoText.text = getString(
            R.string.build_info_format,
            BuildConfig.BUILD_TIME,
            BuildConfig.GIT_COMMIT,
            BuildConfig.VERSION_CODE
        )

        setupListeners()
        setupNotesRecyclerView()
        updateUiState()
        updateNotesDisplay()
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
        updateNotesDisplay()
    }

    private fun setupListeners() {
        binding.grantPermissionButton.setOnClickListener {
            requestOverlayPermission()
        }

        binding.startOverlayButton.setOnClickListener {
            if (hasOverlayPermission()) {
                startOverlayService()
            } else {
                requestOverlayPermission()
            }
        }

        binding.stopBubbleButton.setOnClickListener {
            stopOverlayService()
        }
    }

    private fun setupNotesRecyclerView() {
        val recyclerView = binding.notesRecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = NotesAdapter(taskStore.getAllNotes(), this::onNoteSelected)
    }

    private fun updateNotesDisplay() {
        val notes = taskStore.getAllNotes()
        binding.overlayStatusBadge.text = if (notes.isNotEmpty()) "${notes.size} note(s)" else "no notes"
        binding.overlayStatusBadge.setTextColor(if (notes.isNotEmpty()) 0xFFA7F3D0.toInt() else 0xFF6B7079.toInt())
        binding.notesRecyclerView.adapter = NotesAdapter(notes, this::onNoteSelected)
    }

    private fun onNoteSelected(noteId: Long) {
        Toast.makeText(this, "Note: $noteId", Toast.LENGTH_SHORT).show()
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
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
            binding.grantPermissionButton.visibility = View.GONE
            binding.startOverlayButton.isEnabled = true
        } else {
            binding.permissionStatusText.setText(R.string.permission_required)
            binding.grantPermissionButton.visibility = View.VISIBLE
            binding.startOverlayButton.isEnabled = false
        }

        if (OverlayService.isRunning) {
            binding.serviceStatusText.setText(R.string.overlay_status_running)
            binding.stopBubbleButton.isEnabled = true
            binding.overlayStatusBadge.text = "active"
        } else {
            binding.serviceStatusText.setText(R.string.overlay_status_stopped)
            binding.stopBubbleButton.isEnabled = false
            binding.overlayStatusBadge.text = "inactive"
        }
    }
}