package com.krdonon.timer

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
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

            title.text = theme.title
            title.setTextColor(theme.previewText)

            subtitle.text = theme.subtitle
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
