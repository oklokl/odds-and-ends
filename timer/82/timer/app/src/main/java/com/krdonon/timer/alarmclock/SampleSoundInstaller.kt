package com.krdonon.timer.alarmclock

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.krdonon.timer.alarm.AlarmService
import java.io.IOException

/**
 * Copies a bundled sample mp3 (assets/sample_alarm.mp3) to the user's Downloads folder.
 *
 * minSdk = 26. For API 29+ we can write to Downloads via MediaStore without storage permissions.
 * For API 26~28 we simply skip auto-copy to avoid legacy storage permissions.
 */
object SampleSoundInstaller {

    private const val PREFS = AlarmService.PREFS
    private const val KEY_INSTALLED = "sample_sound_installed_v1"
    private const val KEY_URI = "sample_sound_uri_v1"

    fun ensureInstalled(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val already = prefs.getBoolean(KEY_INSTALLED, false)
        if (already) {
            val s = prefs.getString(KEY_URI, null)
            return s?.let { runCatching { Uri.parse(it) }.getOrNull() }
        }

        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            installViaMediaStore(context)
        } else {
            // API 26~28: avoid WRITE_EXTERNAL_STORAGE
            null
        }

        prefs.edit()
            .putBoolean(KEY_INSTALLED, true)
            .putString(KEY_URI, uri?.toString())
            .apply()

        return uri
    }

    private fun installViaMediaStore(context: Context): Uri? {
        val resolver = context.contentResolver
        val fileName = "sample_alarm.mp3"
        val mime = "audio/mpeg"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values) ?: return null

        try {
            resolver.openOutputStream(itemUri)?.use { out ->
                context.assets.open("sample_alarm.mp3").use { input ->
                    input.copyTo(out)
                }
            } ?: throw IOException("openOutputStream returned null")
        } catch (e: Throwable) {
            // cleanup
            runCatching { resolver.delete(itemUri, null, null) }
            return null
        }

        // finalize
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        runCatching { resolver.update(itemUri, done, null, null) }

        return itemUri
    }
}
