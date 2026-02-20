package com.krdonon.timer.alarm

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Timer-only preferences (must NOT affect Alarm menu settings).
 */
object TimerAlarmPrefs {
    private const val PREFS = AlarmService.PREFS

    private const val KEY_TIMER_CUSTOM_SOUND_URI = "key_timer_custom_sound_uri"
    private const val KEY_TIMER_CUSTOM_SOUND_NAME = "key_timer_custom_sound_name"
    private const val KEY_TIMER_CUSTOM_SOUND_LOCAL_PATH = "key_timer_custom_sound_local_path"

    const val KEY_TIMER_RING_DURATION_MINUTES = "key_timer_ring_duration_minutes" // 5..60
    const val KEY_TIMER_RING_FOREVER = "key_timer_ring_forever" // true -> no auto stop

    fun getCustomUri(context: Context): Uri? {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TIMER_CUSTOM_SOUND_URI, null)
            ?: return null
        return runCatching { Uri.parse(s) }.getOrNull()
    }

    fun getCustomName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TIMER_CUSTOM_SOUND_NAME, null)

    fun getCustomLocalPath(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TIMER_CUSTOM_SOUND_LOCAL_PATH, null)

    fun setCustom(context: Context, uri: Uri, displayName: String?, localPath: String? = null) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TIMER_CUSTOM_SOUND_URI, uri.toString())
            .putString(KEY_TIMER_CUSTOM_SOUND_NAME, displayName ?: uri.toString())
            .putString(KEY_TIMER_CUSTOM_SOUND_LOCAL_PATH, localPath)
            .apply()
    }

    fun clearCustom(context: Context) {
        // 내부 복사본도 같이 정리
        deleteInternalCopyIfAny(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_TIMER_CUSTOM_SOUND_URI)
            .remove(KEY_TIMER_CUSTOM_SOUND_NAME)
            .remove(KEY_TIMER_CUSTOM_SOUND_LOCAL_PATH)
            .apply()
    }

    private fun deleteInternalCopyIfAny(context: Context) {
        val p = getCustomLocalPath(context)
        if (!p.isNullOrBlank()) {
            runCatching { java.io.File(p).delete() }
        }
    }

    /**
     * Validate the previously selected timer mp3.
     *
     * 요구사항: 사용자가 파일 앱에서 mp3를 삭제한 경우, 타이머는 더 이상 그 소리를 사용하면 안 되고
     * 기본 사운드로 돌아가야 함.
     *
     * 따라서 "앱 내부 복사본"이 있더라도 원본 Uri가 더 이상 열리지 않으면(삭제/이동/권한 철회)
     * 커스텀 설정을 모두 정리하고 기본으로 복귀한다.
     */
    fun validateOrResetToDefault(context: Context): Boolean {
        val uri = getCustomUri(context)

        // Uri 없이 로컬만 남아있는 비정상 상태면 정리
        if (uri == null) {
            if (!getCustomLocalPath(context).isNullOrBlank()) {
                deleteInternalCopyIfAny(context)
                clearCustom(context)
            }
            return false
        }

        val readable = runCatching {
            context.contentResolver.openInputStream(uri)?.use { }
            true
        }.getOrDefault(false)

        if (!readable) {
            deleteInternalCopyIfAny(context)
            clearCustom(context)
            return false
        }
        return true
    }

    /**
     * Returns a Uri that is currently readable. If the saved Uri isn't readable anymore,
     * this will clear it and return null.
     */
    fun getReadableCustomUriOrNull(context: Context): Uri? {
        val uri = getCustomUri(context) ?: return null
        // 원본이 더 이상 열리지 않으면(삭제 포함) 타이머는 기본으로 복귀해야 한다.
        return if (validateOrResetToDefault(context)) uri else null
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
     * IMPORTANT: takePersistableUriPermission must use the flags returned by the picker intent.
     */
    fun persistReadPermission(context: Context, uri: Uri, resultFlags: Int) {
        val takeFlags = resultFlags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        }
    }

    /**
     * Copy selected Uri into app-private storage so the timer sound works even when
     * persistable permission is not granted by the provider.
     */
    fun copyToInternalStorage(context: Context, uri: Uri, displayName: String?): String? {
        return runCatching {
            val dir = java.io.File(context.filesDir, "timer_sounds").apply { mkdirs() }

            val ext = displayName
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.takeIf { it.isNotBlank() }
                ?.let { ".${it}" }
                ?: ".mp3"

            val outFile = java.io.File(dir, "timer_sound_${java.util.UUID.randomUUID()}$ext")

            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            outFile.absolutePath
        }.getOrNull()
    }
}
