package com.mertkaradag.stepviewer

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/** İlk açılışta (ve Ayarlar > Nasıl kullanılır) gösterilen kısa tanıtım. */
class Onboarding(private val context: Context, private val onDone: () -> Unit) {

    private class Page(val icon: Int, val title: Int, val body: Int)

    private val pages = listOf(
        Page(R.drawable.ic_folder_open, R.string.tour_open_title, R.string.tour_open_body),
        Page(R.drawable.ic_touch, R.string.tour_gestures_title, R.string.tour_gestures_body),
        Page(R.drawable.ic_ruler, R.string.tour_measure_title, R.string.tour_measure_body),
        Page(R.drawable.ic_layers, R.string.tour_parts_title, R.string.tour_parts_body),
        Page(R.drawable.ic_settings, R.string.tour_settings_title, R.string.tour_settings_body),
    )

    private val d = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private var index = 0

    fun show() {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0))

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = 28 * d
                setColor(context.getColor(R.color.surface_solid))
            }
        }
        val iconBox = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFFF9A4D.toInt(), 0xFFE8650E.toInt()),
            ).apply { shape = GradientDrawable.OVAL }
        }
        val icon = ImageView(context).apply { imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt()) }
        iconBox.addView(icon, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        card.addView(iconBox, LinearLayout.LayoutParams(dp(84), dp(84)))

        val title = TextView(context).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getColor(R.color.text))
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(8))
        }
        card.addView(title)
        val body = TextView(context).apply {
            textSize = 15f
            setLineSpacing(0f, 1.2f)
            setTextColor(context.getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
            minLines = 5
        }
        card.addView(body)

        val dots = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }
        card.addView(dots)

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val skip = MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = context.getString(R.string.tour_skip)
        }
        val next = MaterialButton(context).apply { cornerRadius = dp(16) }
        buttons.addView(skip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(next, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun render() {
            val p = pages[index]
            icon.setImageResource(p.icon)
            title.setText(p.title)
            body.setText(p.body)
            dots.removeAllViews()
            for (i in pages.indices) {
                dots.addView(android.view.View(context).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = 4 * d
                        setColor(context.getColor(if (i == index) R.color.accent else R.color.outline))
                    }
                }, LinearLayout.LayoutParams(if (i == index) dp(22) else dp(8), dp(8)).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                })
            }
            next.text = context.getString(if (index == pages.size - 1) R.string.tour_start else R.string.tour_next)
            skip.visibility = if (index == pages.size - 1) android.view.View.INVISIBLE else android.view.View.VISIBLE
        }

        skip.setOnClickListener { dialog.dismiss() }
        next.setOnClickListener {
            if (index < pages.size - 1) {
                index++
                render()
            } else {
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener { onDone() }
        render()

        val wrap = FrameLayout(context).apply { setPadding(dp(20), 0, dp(20), 0) }
        wrap.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        dialog.setContentView(wrap)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }
}
