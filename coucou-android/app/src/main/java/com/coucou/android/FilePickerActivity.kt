package com.coucou.android

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONObject

/**
 * SPRINT 6.7 — the transparent SAF launcher behind the Vault's "Choose file".
 *
 * [IslandBridgeHost.openFilePicker] is called with the overlay's [OverlayService]
 * context. A Service cannot receive `onActivityResult`, so the old
 * `startActivityForResult` there dead-ended on every tap. This activity is the missing
 * `Activity` context: it is translucent and has no UI, it launches
 * `ACTION_OPEN_DOCUMENT`, resolves the picked document's name / size / MIME type / URI
 * and hands it back to the page as `window.CoucouAndroid.onFileSelected(json)` through
 * [OverlayService.deliverFileSelection], then finishes.
 */
class FilePickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated (rotation) while the picker is up: the system re-delivers the result,
        // so only launch on the first pass.
        if (savedInstanceState == null) launchPicker()
    }

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("*/*"))
        }
        try {
            // `createChooser` guarantees the intent resolves even when the device has no
            // default DocumentsUI handler for the MIME type.
            startActivityForResult(Intent.createChooser(intent, CHOOSER_TITLE), REQUEST_PICK)
        } catch (e: Exception) {
            Log.w(TAG, "No document provider could handle ACTION_OPEN_DOCUMENT", e)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                runCatching { deliver(uri) }.onFailure { Log.w(TAG, "Could not deliver $uri", it) }
            }
        }
        finish()
    }

    /** Serialises the picked document and pushes it into the island's WebView. */
    private fun deliver(uri: Uri) {
        val meta = queryMeta(uri)
        val json = JSONObject()
            .put("name", meta.name)
            .put("size", meta.size)
            .put("mimeType", contentResolver.getType(uri) ?: FALLBACK_MIME)
            .put("uri", uri.toString())
            .toString()
        OverlayService.deliverFileSelection(json)
    }

    private data class Meta(val name: String, val size: Long)

    /**
     * Reads the display name / size the picker's provider exposes. `contentResolver` works
     * even for `content://` URIs (a plain path read would not), and the URI's last segment
     * is the fallback when a provider reports neither column.
     */
    private fun queryMeta(uri: Uri): Meta {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: FALLBACK_NAME
        var size = 0L
        try {
            val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
            cursor?.use { c ->
                val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (nameIndex >= 0 && !c.isNull(nameIndex)) name = c.getString(nameIndex)
                    if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read metadata for $uri", e)
        }
        return Meta(name, size)
    }

    companion object {
        private const val TAG = "CoucouFilePicker"
        private const val REQUEST_PICK = 1001
        private const val CHOOSER_TITLE = "Select file"
        private const val FALLBACK_MIME = "application/octet-stream"
        private const val FALLBACK_NAME = "file"
    }
}
