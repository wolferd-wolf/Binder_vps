package com.coucou.android

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.util.Log

/**
 * SPRINT 6.7 — the transparent bridge that shows Android's native RECORD_AUDIO prompt.
 *
 * The Live Voice view asks for the mic from the page, and the page's host is
 * [OverlayService]. A Service cannot request a runtime permission, which is why the old
 * route fell back to the raw app-settings screen. This activity supplies the `Activity`
 * context: it is translucent and has no UI of its own, it either reports the permission
 * as already granted or calls `requestPermissions` (so the system draws its own dialog),
 * and it finishes as soon as the user answers — the island stays visible behind it.
 */
class MicPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Runtime permissions do not exist below API 23; a manifest grant is enough.
            OverlayService.deliverMicPermission(true)
            finish()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            // Granted in the meantime (or between the check and this launch).
            OverlayService.deliverMicPermission(true)
            finish()
            return
        }
        try {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not request RECORD_AUDIO", e)
            OverlayService.deliverMicPermission(false)
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            OverlayService.deliverMicPermission(granted)
            finish()
        }
    }

    companion object {
        private const val TAG = "CoucouMicPermission"
        private const val REQUEST_RECORD_AUDIO = 2001
    }
}
