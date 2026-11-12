package com.mertkaradag.stepviewer

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

/**
 * Delik tablosu listesi: her satır bir delik tipi (A, B, ...). Satıra dokununca o tipteki
 * delikler modelde vurgulanır.
 */
class HoleTableSheet(
    private val context: Context,
    private val groups: List<Holes.Group>,
    private val totalHoles: Int,
    private val onSelect: (Holes.Group) -> Unit,
    private val onSaveCsv: () -> Unit,
    private val onAddToReport: (() -> Unit)? = null,
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    fun show() {
        val dialog = BottomSheetDialog(context)
        val colorPrimary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF1565C0.toInt())
        val onPrimary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimary, 0xFFFFFFFF.toInt())
        val textSecondary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666.toInt())

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.menu_holes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val save = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = context.getString(R.string.holes_save_csv)
            setIconResource(R.drawable.ic_download)
            setOnClickListener {
                dialog.dismiss()
                onSaveCsv()
            }
        }
        header.addView(save)
        if (onAddToReport != null) {
            header.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = context.getString(R.string.qc_holes_to_report)
                setOnClickListener {
                    dialog.dismiss()
                    onAddToReport.invoke()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        }
        root.addView(header)

        root.addView(TextView(context).apply {
            text = context.getString(R.string.holes_summary, totalHoles, groups.size) + "\n" +
                context.getString(R.string.holes_hint)
            setTextColor(textSecondary)
            setPadding(0, dp(4), 0, dp(8))
        })

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (g in groups) {
            val h = g.first
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                val tv = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    onSelect(g)
                }
            }
            val badge = TextView(context).apply {
                text = g.label
                gravity = Gravity.CENTER
                setTextColor(onPrimary)
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorPrimary)
                }
            }
            row.addView(badge, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) })
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(context).apply {
                text = Holes.title(context, h)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            val det = Holes.details(context, h)
            if (det.isNotEmpty()) {
                texts.addView(TextView(context).apply {
                    text = det.joinToString(" · ")
                    setTextColor(textSecondary)
                })
            }
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(context).apply {
                text = context.getString(R.string.holes_count, g.holes.size)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), 0, 0, 0)
            })
            list.addView(row)
            list.addView(View(context).apply {
                setBackgroundColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0x22000000))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }
        val scroll = NestedScrollView(context).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialog.setContentView(root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }
}
