package com.mertkaradag.stepviewer

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton

/**
 * Teknik resim önizlemesi: iki parmakla yakınlaştır, kâğıt boyutu (A4/A3), PDF ve DXF kaydet, paylaş.
 */
class DrawingDialog(
    private val context: Context,
    private val views: TechDrawing.Views,
    private val texts: TechDrawing.Texts,
    private val onSavePdf: (TechDrawing.Layout) -> Unit,
    private val onSaveDxf: (TechDrawing.Layout) -> Unit,
    private val onSharePdf: (TechDrawing.Layout) -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var paper = TechDrawing.Paper.A4
    private var layout = TechDrawing.layout(views, paper)
    private var bitmap: Bitmap? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(32, 34, 38))
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(8), dp(4))
        }
        val close = iconButton(R.drawable.ic_close, R.string.section_close)
        top.addView(close)
        val info = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(4), 0, dp(4), 0)
        }
        top.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val paperBtn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setTextColor(Color.WHITE)
            strokeColor = ColorStateList.valueOf(Color.argb(140, 255, 255, 255))
            contentDescription = context.getString(R.string.drawing_paper)
        }
        top.addView(paperBtn)
        root.addView(top)

        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setBackgroundColor(Color.rgb(70, 72, 78))
        }
        root.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        fun action(text: Int, icon: Int, click: () -> Unit) = MaterialButton(context).apply {
            setText(text)
            setIconResource(icon)
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        }
        bottom.addView(action(R.string.drawing_pdf, R.drawable.ic_save) { onSavePdf(layout) })
        bottom.addView(action(R.string.drawing_dxf, R.drawable.ic_download) { onSaveDxf(layout) })
        bottom.addView(action(R.string.menu_share, R.drawable.ic_share) { onSharePdf(layout) })
        root.addView(bottom)

        // Yakınlaştırma / kaydırma
        val matrix = Matrix()
        fun fit() {
            val b = bitmap ?: return
            if (image.width == 0) return
            val src = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
            val dst = RectF(dp(8).toFloat(), dp(8).toFloat(), image.width - dp(8).toFloat(), image.height - dp(8).toFloat())
            matrix.setRectToRect(src, dst, Matrix.ScaleToFit.CENTER)
            image.imageMatrix = matrix
        }
        fun render() {
            layout = TechDrawing.layout(views, paper)
            val old = bitmap
            bitmap = try {
                TechDrawing.bitmap(views, layout, texts, if (paper == TechDrawing.Paper.A3) 4f else 5f)
            } catch (_: OutOfMemoryError) {
                TechDrawing.bitmap(views, layout, texts, 2.5f)
            }
            image.setImageBitmap(bitmap)
            old?.recycle()
            paperBtn.text = paper.label
            info.text = context.getString(R.string.drawing_info, layout.scaleText)
            image.post { fit() }
        }
        paperBtn.setOnClickListener {
            paper = if (paper == TechDrawing.Paper.A4) TechDrawing.Paper.A3 else TechDrawing.Paper.A4
            render()
        }
        val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                matrix.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
                image.imageMatrix = matrix
                return true
            }
        })
        val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                matrix.postTranslate(-dx, -dy)
                image.imageMatrix = matrix
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                fit()
                return true
            }
        })
        image.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            gestures.onTouchEvent(ev)
            true
        }
        image.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) fit()
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }
        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            image.setImageDrawable(null)
            bitmap?.recycle()
            bitmap = null
        }
        dialog.setContentView(root)
        dialog.show()
        render()
    }

    private fun iconButton(icon: Int, label: Int): MaterialButton =
        MaterialButton(context, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            setIconResource(icon)
            iconTint = ColorStateList.valueOf(Color.WHITE)
            iconSize = dp(24)
            contentDescription = context.getString(label)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        }
}
