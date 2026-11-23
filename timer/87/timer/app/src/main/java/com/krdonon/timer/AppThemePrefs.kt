package com.krdonon.timer

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue
import androidx.activity.SystemBarStyle
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes

enum class AppColorTheme(
    val id: String,
    val title: String,
    val subtitle: String,
    @param:StyleRes val styleRes: Int,
    val isDarkPalette: Boolean?,
    val previewBg: Int,
    val previewCard: Int,
    val previewBorder: Int,
    val previewAccent: Int,
    val previewText: Int,
) {
    ORIGINAL(
        id = "original",
        title = "오리지널",
        subtitle = "기본 민트 · 자동 다크",
        styleRes = R.style.Theme_Timer,
        isDarkPalette = null,
        previewBg = 0xFFE4F3E1.toInt(),
        previewCard = 0xFFF7FCF6.toInt(),
        previewBorder = 0xFFC7DEC4.toInt(),
        previewAccent = 0xFF1F6E43.toInt(),
        previewText = 0xFF132219.toInt(),
    ),
    AMOLED_BLACK(
        id = "amoled_black",
        title = "퓨어 블랙",
        subtitle = "배터리 절약 · 흰 테두리",
        styleRes = R.style.Theme_Timer_AmoledBlack,
        isDarkPalette = true,
        previewBg = 0xFF000000.toInt(),
        previewCard = 0xFF000000.toInt(),
        previewBorder = 0xFFFFFFFF.toInt(),
        previewAccent = 0xFFFFFFFF.toInt(),
        previewText = 0xFFFFFFFF.toInt(),
    ),
    PASTEL_BLUE(
        id = "pastel_blue",
        title = "연파랑",
        subtitle = "맑은 하늘빛 블루",
        styleRes = R.style.Theme_Timer_PastelBlue,
        isDarkPalette = false,
        previewBg = 0xFFE3F0FC.toInt(),
        previewCard = 0xFFF7FBFF.toInt(),
        previewBorder = 0xFFC0D7EE.toInt(),
        previewAccent = 0xFF1D63B8.toInt(),
        previewText = 0xFF102033.toInt(),
    ),
    PASTEL_PINK(
        id = "pastel_pink",
        title = "연분홍",
        subtitle = "부드러운 블라썸 핑크",
        styleRes = R.style.Theme_Timer_PastelPink,
        isDarkPalette = false,
        previewBg = 0xFFFCE9F0.toInt(),
        previewCard = 0xFFFFF8FA.toInt(),
        previewBorder = 0xFFECC7D6.toInt(),
        previewAccent = 0xFFB8326A.toInt(),
        previewText = 0xFF2E1320.toInt(),
    ),
    COBALT_RED(
        id = "cobalt_red",
        title = "코발트 빨강",
        subtitle = "진한 레드 · 코발트 포인트",
        styleRes = R.style.Theme_Timer_CobaltRed,
        isDarkPalette = true,
        previewBg = 0xFF991B1B.toInt(),
        previewCard = 0xFFB91C1C.toInt(),
        previewBorder = 0xFFF87171.toInt(),
        previewAccent = 0xFF1D3A8A.toInt(),
        previewText = 0xFFFFFFFF.toInt(),
    ),
    LAVENDER(
        id = "lavender",
        title = "라벤더",
        subtitle = "AI 추천 · 은은한 보라",
        styleRes = R.style.Theme_Timer_Lavender,
        isDarkPalette = false,
        previewBg = 0xFFEFEAFC.toInt(),
        previewCard = 0xFFFAF8FF.toInt(),
        previewBorder = 0xFFD2C7EC.toInt(),
        previewAccent = 0xFF5E3BB5.toInt(),
        previewText = 0xFF1E1633.toInt(),
    ),
    WARM_SAND(
        id = "warm_sand",
        title = "샌드 앰버",
        subtitle = "AI 추천 · 따뜻한 크림",
        styleRes = R.style.Theme_Timer_WarmSand,
        isDarkPalette = false,
        previewBg = 0xFFF5EEE1.toInt(),
        previewCard = 0xFFFCF9F2.toInt(),
        previewBorder = 0xFFE0D1BA.toInt(),
        previewAccent = 0xFFA35814.toInt(),
        previewText = 0xFF2C2014.toInt(),
    ),
    DEEP_NAVY(
        id = "deep_navy",
        title = "딥 네이비",
        subtitle = "AI 추천 · 편안한 야간",
        styleRes = R.style.Theme_Timer_DeepNavy,
        isDarkPalette = true,
        previewBg = 0xFF0E1628.toInt(),
        previewCard = 0xFF17223B.toInt(),
        previewBorder = 0xFF2B3B5E.toInt(),
        previewAccent = 0xFF6FA8FF.toInt(),
        previewText = 0xFFECF2FF.toInt(),
    );

    companion object {
        fun fromId(id: String?): AppColorTheme =
            entries.firstOrNull { it.id == id } ?: ORIGINAL
    }
}

object AppThemePrefs {
    private const val PREFS_NAME = "app_theme_prefs"
    private const val KEY_THEME_ID = "selected_color_theme_id"

    fun getSelectedTheme(context: Context): AppColorTheme {
        val id = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_ID, AppColorTheme.ORIGINAL.id)
        return AppColorTheme.fromId(id)
    }

    fun setSelectedTheme(context: Context, theme: AppColorTheme) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_ID, theme.id)
            .apply()
    }

    fun applyTheme(activity: Activity) {
        activity.setTheme(getSelectedTheme(activity).styleRes)
    }

    fun isDarkActive(context: Context): Boolean {
        val selected = getSelectedTheme(context)
        selected.isDarkPalette?.let { return it }
        val nightFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightFlags == Configuration.UI_MODE_NIGHT_YES
    }

    fun systemBarStyle(context: Context): SystemBarStyle =
        if (isDarkActive(context)) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }

    fun resolveColor(context: Context, @AttrRes attrRes: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attrRes, tv, true)) {
            if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
        } else {
            Color.TRANSPARENT
        }
    }

    fun resolveDimensionPx(context: Context, @AttrRes attrRes: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attrRes, tv, true)) {
            TypedValue.complexToDimensionPixelSize(tv.data, context.resources.displayMetrics)
        } else {
            0
        }
    }
}
