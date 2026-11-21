package com.krdondon.week

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.core.content.edit

/**
 * 위젯 배경 스타일 목록 및 사용자 선택값 저장소.
 *
 * 단색 민트 배경 + SVG 기반 벡터 민트 카드 3종을 순환 선택한다.
 */
enum class WidgetBackground(
    val id: String,
    val label: String,
    @param:DrawableRes val drawableRes: Int,
) {
    SOLID_MINT(
        id = "solid_mint",
        label = "기본 민트 단색",
        drawableRes = R.drawable.bg_widget_solid_mint,
    ),
    MINT_ORIGINAL(
        id = "mint_01",
        label = "민트 웨이브 1 (오리지널)",
        drawableRes = R.drawable.bg_widget_mint_01,
    ),
    MINT_SOFT_WAVES(
        id = "mint_02",
        label = "민트 웨이브 2 (소프트)",
        drawableRes = R.drawable.bg_widget_mint_02,
    ),
    MINT_CORNER_LAYERS(
        id = "mint_03",
        label = "민트 웨이브 3 (코너 레이어)",
        drawableRes = R.drawable.bg_widget_mint_03,
    ),
    ;

    fun next(): WidgetBackground {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }

    companion object {
        private const val PREFS_NAME = "week_widget_prefs"
        private const val KEY_BACKGROUND_ID = "widget_background_id"

        fun fromId(id: String?): WidgetBackground {
            return entries.firstOrNull { it.id == id } ?: SOLID_MINT
        }

        fun getSelected(context: Context): WidgetBackground {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return fromId(prefs.getString(KEY_BACKGROUND_ID, null))
        }

        fun setSelected(context: Context, background: WidgetBackground) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_BACKGROUND_ID, background.id)
            }
        }

        fun cycleNext(context: Context): WidgetBackground {
            val next = getSelected(context).next()
            setSelected(context, next)
            return next
        }
    }
}
