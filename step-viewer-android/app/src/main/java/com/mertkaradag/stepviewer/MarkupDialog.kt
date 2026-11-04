package com.mertkaradag.stepviewer

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Tam ekran işaretleme: ekran görüntüsü üstüne kalem, ok, çerçeve ve yazı;
 * sonra paylaş veya kaydet.
 */
class MarkupDialog(
    private val context: Context,
    private val image: Bitmap,
    private val onShare: (Bitmap) -> Unit,
    private val onSave: (Bitmap) -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    fun show() {
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val markup = MarkupView(context, image)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 18, 22))
        }

        // Üst çubuk: kapat, geri al, kaydet, paylaş
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val close = iconButton(R.drawable.ic_close, R.string.section_close)
        val undo = iconButton(R.drawable.ic_undo, R.string.undo)
        val save = iconButton(R.drawable.ic_save, R.string.markup_save)
        val share = iconButton(R.drawable.ic_share, R.string.menu_share)
        top.addView(close)
        top.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(undo)
        top.addView(save)
        top.addView(share)
        undo.isEnabled = false
        undo.alpha = 0.4f

        markup.onChanged = {
            undo.isEnabled = markup.canUndo
            undo.alpha = if (markup.canUndo) 1f else 0.4f
        }
        markup.onTextRequested = { x, y ->
            val edit = EditText(context).apply {
                setSingleLine()
                hint = context.getString(R.string.markup_text_hint)
            }
            val box = FrameLayout(context).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(edit)
            }
            val d = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.markup_text)
                .setView(box)
                .setPositiveButton(R.string.ok) { _, _ -> markup.addText(x, y, edit.text.toString()) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            edit.requestFocus()
        }

        // Alt çubuk: araçlar ve renkler
        val tools = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val toolButtons = listOf(
            MarkupView.Tool.PEN to iconButton(R.drawable.ic_pen, R.string.markup_pen),
            MarkupView.Tool.ARROW to iconButton(R.drawable.ic_arrow, R.string.markup_arrow),
            MarkupView.Tool.RECT to iconButton(R.drawable.ic_rect, R.string.markup_rect),
            MarkupView.Tool.TEXT to iconButton(R.drawable.ic_text, R.string.markup_text),
        )
        fun selectTool(t: MarkupView.Tool) {
            markup.tool = t
            for ((tool, b) in toolButtons) {
                b.backgroundTintList = ColorStateList.valueOf(
                    if (tool == t) Color.argb(90, 255, 255, 255) else Color.TRANSPARENT,
                )
            }
        }
        for ((t, b) in toolButtons) {
            b.setOnClickListener { selectTool(t) }
            tools.addView(b)
        }
        selectTool(MarkupView.Tool.PEN)

        val colors = intArrayOf(
            Color.rgb(255, 82, 82), Color.rgb(255, 152, 0), Color.rgb(255, 235, 59),
            Color.rgb(76, 175, 80), Color.rgb(66, 165, 245), Color.WHITE, Color.BLACK,
        )
        val colorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        }
        val swatches = ArrayList<View>()
        fun selectColor(c: Int) {
            markup.color = c
            for (s in swatches) {
                val sc = s.tag as Int
                (s.background as GradientDrawable).setStroke(
                    if (sc == c) dp(3) else dp(1),
                    if (sc == c) Color.WHITE else Color.argb(120, 255, 255, 255),
                )
            }
        }
        for (c in colors) {
            val v = View(context).apply {
                tag = c
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                }
                contentDescription = "#" + Integer.toHexString(c).takeLast(6)
                setOnClickListener { selectColor(c) }
            }
            swatches += v
            colorRow.addView(v, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            })
        }
        selectColor(colors[0])

        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(markup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(colorRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        close.setOnClickListener { dialog.dismiss() }
        undo.setOnClickListener { markup.undo() }
        share.setOnClickListener {
            onShare(markup.render())
            dialog.dismiss()
        }
        save.setOnClickListener {
            onSave(markup.render())
        }

        dialog.setContentView(root)
        dialog.show()
    }

    private fun iconButton(icon: Int, label: Int): MaterialButton =
        MaterialButton(context, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            setIconResource(icon)
            iconTint = ColorStateList.valueOf(Color.WHITE)
            iconSize = dp(24)
            contentDescription = context.getString(label)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
        }
}
