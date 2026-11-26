package com.krdondon.thelordsprayer

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import androidx.core.content.edit

/**
 * 음성 세트(`assets/sounds/setX`) 목록 조회, 순환(이전/다음), 선택 상태 저장을 담당하는 공통 저장소.
 */
object VoiceSetStore {
    const val PREFS_NAME = "voice_prefs"
    const val KEY_SELECTED_VOICE_SET = "selected_voice_set"
    const val DEFAULT_VOICE_LABEL = "기본"
    const val ASSET_SOUND_ROOT = "sounds"

    private const val TAG = "VoiceSetStore"
    private val SET_DIR_REGEX = Regex("""^set\d+$""")

    @Volatile
    private var cachedVoiceSets: List<String>? = null

    fun parseAndSortVoiceSets(names: Iterable<String>): List<String> {
        return names
            .filter { SET_DIR_REGEX.matches(it) }
            .sortedBy { it.removePrefix("set").toIntOrNull() ?: Int.MAX_VALUE }
    }

    fun listAvailableVoiceSets(assets: AssetManager, forceRefresh: Boolean = false): List<String> {
        if (!forceRefresh) {
            cachedVoiceSets?.let { return it }
        }
        val sets = try {
            val entries = assets.list(ASSET_SOUND_ROOT) ?: emptyArray()
            parseAndSortVoiceSets(entries.asIterable())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list assets/$ASSET_SOUND_ROOT: ${e.message}")
            emptyList()
        }
        cachedVoiceSets = sets
        return sets
    }

    fun clearCache() {
        cachedVoiceSets = null
    }

    fun getNextVoiceSet(sets: List<String>, current: String?): String? {
        if (sets.isEmpty()) return null
        val currentIdx = sets.indexOf(current)
        val nextIdx = if (currentIdx < 0) 0 else (currentIdx + 1) % sets.size
        return sets[nextIdx]
    }

    fun getPreviousVoiceSet(sets: List<String>, current: String?): String? {
        if (sets.isEmpty()) return null
        val currentIdx = sets.indexOf(current)
        val prevIdx = if (currentIdx < 0) {
            sets.lastIndex
        } else {
            (currentIdx - 1 + sets.size) % sets.size
        }
        return sets[prevIdx]
    }

    fun loadSelectedVoiceSet(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_SELECTED_VOICE_SET, "") ?: ""
        return saved.ifBlank { null }
    }

    fun saveSelectedVoiceSet(context: Context, setName: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putString(KEY_SELECTED_VOICE_SET, setName ?: "")
        }
    }

    fun formatVoiceLabel(setName: String?): String {
        return setName?.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE_LABEL
    }
}
