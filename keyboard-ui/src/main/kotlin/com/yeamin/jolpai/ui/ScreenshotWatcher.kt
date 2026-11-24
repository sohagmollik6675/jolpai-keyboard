package com.yeamin.jolpai.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.content.ContentUris
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Finds the newest screenshot taken just now, so the keyboard can offer it like Gboard does.
 *
 * - Needs the photo permission ([hasPermission]); the app asks for it on a settings screen,
 *   because a keyboard can't show a permission prompt itself.
 * - Watches the MediaStore for new pictures and only reacts to screenshots (file name starts
 *   with "Screenshot"), taken within [maxAgeMillis].
 * - Queries run off the main thread; [onNew] is called on the main thread, once per screenshot.
 */
class ScreenshotWatcher(
    private val context: Context,
    private val maxAgeMillis: Long = 60_000L,
    private val onNew: (Screenshot) -> Unit,
) {
    data class Screenshot(val id: Long, val uri: Uri, val mimeType: String)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var observer: ContentObserver? = null
    private var lastId = -1L

    fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** Starts watching while the keyboard is shown. Does nothing without permission. */
    fun start() {
        if (observer != null || !hasPermission()) return
        val o = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = check()
        }
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o)
        observer = o
    }

    fun stop() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    /** Checks for a new screenshot now, e.g. when the keyboard appears. */
    fun check() {
        if (!hasPermission()) return
        worker.execute {
            val found = latest()
            main.post {
                if (found != null && found.id != lastId) {
                    lastId = found.id
                    onNew(found)
                }
            }
        }
    }

    /** The newest screenshot taken within [maxAgeMillis], or null. Blocking; call off the main thread. */
    fun latest(): Screenshot? {
        val nowSeconds = System.currentTimeMillis() / 1000
        val since = nowSeconds - maxAgeMillis / 1000
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val args = arrayOf("Screenshot%", since.toString())
        return runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 1",
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val mime = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)) ?: "image/png"
                Screenshot(id, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), mime)
            }
        }.getOrNull()
    }
}
