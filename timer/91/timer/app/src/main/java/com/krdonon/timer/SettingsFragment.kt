package com.krdonon.timer

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val grid = view.findViewById<GridLayout>(R.id.themeGrid)
        populateThemeSwatches(grid)
        setupLanguageButtons(view)
    }

    private fun setupLanguageButtons(view: View) {
        val ctx = requireContext()
        val btnKr = view.findViewById<MaterialButton>(R.id.btnLangKr)
        val btnEn = view.findViewById<MaterialButton>(R.id.btnLangEn)

        val currentTheme = AppThemePrefs.getSelectedTheme(ctx)
        val isAmoled = (currentTheme == AppColorTheme.AMOLED_BLACK)
        val density = resources.displayMetrics.density

        val selectedBg = if (isAmoled) Color.WHITE else AppThemePrefs.resolveColor(ctx, R.attr.appAccentPrimary)
        val selectedFg = if (isAmoled) Color.BLACK else AppThemePrefs.resolveColor(ctx, R.attr.appOnAccentPrimary)
        val selectedStroke = if (isAmoled) Color.WHITE else selectedBg
        val normalBg = AppThemePrefs.resolveColor(ctx, R.attr.appSurfaceCard)
        val normalFg = AppThemePrefs.resolveColor(ctx, R.attr.appTextPrimary)
        val normalStroke = if (isAmoled) 0xFF666666.toInt() else AppThemePrefs.resolveColor(ctx, R.attr.appStrokeColor)

        fun styleButton(btn: MaterialButton, selected: Boolean) {
            if (selected) {
                btn.backgroundTintList = ColorStateList.valueOf(selectedBg)
                btn.strokeColor = ColorStateList.valueOf(selectedStroke)
                btn.strokeWidth = (1.5f * density).toInt()
                btn.setTextColor(selectedFg)
                btn.typeface = Typeface.create(btn.typeface, Typeface.BOLD)
                btn.elevation = 4f * density
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(normalBg)
                btn.strokeColor = ColorStateList.valueOf(normalStroke)
                btn.strokeWidth = (1f * density).toInt()
                btn.setTextColor(normalFg)
                btn.typeface = Typeface.create(btn.typeface, Typeface.NORMAL)
                btn.elevation = 1f * density
            }
        }

        val currentLang = AppLanguagePrefs.getSelectedLanguage(ctx)
        styleButton(btnKr, currentLang == AppLanguage.KOREAN)
        styleButton(btnEn, currentLang == AppLanguage.ENGLISH)

        fun switchTo(target: AppLanguage) {
            if (AppLanguagePrefs.getSelectedLanguage(ctx) == target) return
            AppLanguagePrefs.setSelectedLanguage(ctx, target)
            ClockService.refreshLanguageIfRunning(ctx)
            ClockNotificationManager.restoreIfActive(ctx)
            requireActivity().recreate()
        }

        btnKr.setOnClickListener { switchTo(AppLanguage.KOREAN) }
        btnEn.setOnClickListener { switchTo(AppLanguage.ENGLISH) }
    }

    private fun populateThemeSwatches(grid: GridLayout) {
        grid.removeAllViews()
        val ctx = requireContext()
        val current = AppThemePrefs.getSelectedTheme(ctx)
        val density = resources.displayMetrics.density
        val marginPx = (6f * density).toInt()
        val radiusPx = 4f * density

        AppColorTheme.entries.forEach { theme ->
            val itemView = layoutInflater.inflate(R.layout.item_theme_swatch, grid, false)
            val card = itemView.findViewById<MaterialCardView>(R.id.swatchCard)
            val title = itemView.findViewById<TextView>(R.id.swatchTitle)
            val badge = itemView.findViewById<TextView>(R.id.swatchSelectedBadge)
            val miniCard = itemView.findViewById<MaterialCardView>(R.id.swatchMiniCard)
            val miniDigits = itemView.findViewById<TextView>(R.id.swatchMiniDigits)
            val accentBar = itemView.findViewById<View>(R.id.swatchAccentBar)
            val subtitle = itemView.findViewById<TextView>(R.id.swatchSubtitle)

            val selected = (theme == current)
            card.setCardBackgroundColor(theme.previewBg)
            card.strokeColor = if (selected) {
                if (theme.isDarkPalette == true) Color.WHITE else theme.previewAccent
            } else {
                theme.previewBorder
            }
            card.strokeWidth = ((if (selected) 2.5f else 1.2f) * density).toInt()

            title.text = getString(theme.titleRes)
            title.setTextColor(theme.previewText)

            subtitle.text = getString(theme.subtitleRes)
            subtitle.setTextColor(theme.previewText)
            subtitle.alpha = 0.78f

            miniCard.setCardBackgroundColor(theme.previewCard)
            miniCard.strokeColor = theme.previewBorder
            miniCard.strokeWidth = ((if (theme == AppColorTheme.AMOLED_BLACK) 1.5f else 1f) * density).toInt()

            miniDigits.setTextColor(theme.previewText)

            accentBar.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radiusPx
                setColor(theme.previewAccent)
            }

            if (selected) {
                badge.visibility = View.VISIBLE
                badge.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10f * density
                    if (theme == AppColorTheme.AMOLED_BLACK) {
                        setColor(Color.BLACK)
                        setStroke((1f * density).toInt(), Color.WHITE)
                    } else {
                        setColor(theme.previewAccent)
                    }
                }
                badge.setTextColor(
                    when (theme) {
                        AppColorTheme.AMOLED_BLACK -> Color.WHITE
                        AppColorTheme.DEEP_NAVY -> 0xFF0B1F3F.toInt()
                        AppColorTheme.COBALT_RED -> 0xFF881313.toInt()
                        else -> Color.WHITE
                    }
                )
            } else {
                badge.visibility = View.GONE
            }

            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                height = (164f * density).toInt()
                setMargins(marginPx, marginPx, marginPx, marginPx)
            }
            card.layoutParams = lp

            card.setOnClickListener {
                if (theme != AppThemePrefs.getSelectedTheme(ctx)) {
                    AppThemePrefs.setSelectedTheme(ctx, theme)
                    requireActivity().recreate()
                }
            }

            grid.addView(itemView)
        }
    }
}
