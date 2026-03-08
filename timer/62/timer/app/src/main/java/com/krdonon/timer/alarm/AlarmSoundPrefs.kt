package com.krdonon.timer.alarm

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Global alarm sound selection.
 *
 * - If [KEY_CUSTOM_SOUND_URI] exists and the Uri is readable -> use it.
 * - If not readable (file deleted, permission revoked, etc.) -> clear and fall back to bundled raw sound.
 */
object AlarmSoundPrefs {
    private const val PREFS = AlarmService.PREFS

    private const val KEY_CUSTOM_SOUND_URI = "key_custom_sound_uri"
    private const val KEY_CUSTOM_SOUND_NAME = "key_custom_sound_name"

    fun getCustomUri(context: Context): Uri? {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_SOUND_URI, null)
            ?: return null
        return runCatching { Uri.parse(s) }.getOrNull()
    }

    fun getCustomName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_SOUND_NAME, null)

    fun setCustom(context: Context, uri: Uri, displayName: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CUSTOM_SOUND_URI, uri.toString())
            .putString(KEY_CUSTOM_SOUND_NAME, displayName ?: uri.toString())
            .apply()
    }

    fun clearCustom(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_CUSTOM_SOUND_URI)
            .remove(KEY_CUSTOM_SOUND_NAME)
            .apply()
    }

    /**
     * Returns a Uri that is currently readable. If the saved Uri isn't readable anymore,
     * this will clear it and return null.
     */
    fun getReadableCustomUriOrNull(context: Context): Uri? {
        val uri = getCustomUri(context) ?: return null
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { /* just test open */ }
            true
        }.getOrDefault(false)

        if (!ok) clearCustom(context)
        return if (ok) uri else null
    }

    fun resolveDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c: Cursor ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx) else null
                    } else null
                }
        }.getOrNull()
    }

    /**
     * Persist read permission for a SAF Uri (ACTION_OPEN_DOCUMENT).
     */
    fun persistReadPermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }
}
