package com.mertkaradag.stepviewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.max
import kotlin.math.min

/** Sac açınımı sonucu ve gösterimi. */
object SheetMetal {

    const val STATUS_OK = 0
    const val STATUS_NOT_PLANAR = 1
    const val STATUS_NO_THICKNESS = 2

    /** Yaygın K faktörleri: DIN 6935'e yakın 0,44 varsayılan. */
    val K_FACTORS = listOf(0.33, 0.38, 0.44, 0.5)

    class Bend(
        val angle: Double,
        val innerRadius: Double,
        val length: Double,
        val allowance: Double,
        val up: Boolean,
        val line: FloatArray,  // x1, y1, x2, y2
    )

    class Result(
        val status: Int,
        val thickness: Double,
        val kFactor: Double,
        val width: Double,
        val height: Double,
        val area: Double,
        val skippedFaces: Int,
        val bends: List<Bend>,
        val outline: FloatArray,
        val bendLines: FloatArray,
        val dxf: ByteArray,
    )

    /** Arka planda çağrılmalı. */
    fun unfold(partId: Int, faceId: Int, k: Double): Result? {
        val data = StepNative.unfoldSheet(partId, faceId, k) ?: return null
        val info = data[1] as DoubleArray
        val b = data[2] as DoubleArray
        val bends = ArrayList<Bend>()
        var i = 0
        while (i + 9 <= b.size) {
            bends += Bend(
                angle = b[i], innerRadius = b[i + 1], length = b[i + 2], allowance = b[i + 3], up = b[i + 4] > 0.5,
                line = floatArrayOf(b[i + 5].toFloat(), b[i + 6].toFloat(), b[i + 7].toFloat(), b[i + 8].toFloat()),
            )
            i += 9
        }
        return Result(
            status = info[0].toInt(),
            thickness = info[1],
            kFactor = info[2],
            width = info[3],
            height = info[4],
            area = info[5],
            skippedFaces = info[6].toInt(),
            bends = bends,
            outline = data[3] as FloatArray,
            bendLines = data[4] as FloatArray,
            dxf = data[0] as ByteArray,
        )
    }

    /** Açınım önizlemesi: kesim çizgileri + kesikli büküm çizgileri ve numaraları. */
    class PreviewView(context: Context, private val r: Result) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0xFF000000.toInt())
            strokeWidth = 1.4f * d
            style = Paint.Style.STROKE
        }
        private val bend = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE53935.toInt()
            strokeWidth = 1.2f * d
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f)
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE53935.toInt()
            textSize = 11 * d
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val aspect = if (r.width > 1e-6) (r.height / r.width).toFloat() else 1f
            val h = (w * aspect).coerceIn(140 * d, 300 * d)
            setMeasuredDimension(w, h.toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val pad = 12 * d
            val sw = (width - 2 * pad) / max(1e-6, r.width).toFloat()
            val sh = (height - 2 * pad) / max(1e-6, r.height).toFloat()
            val s = min(sw, sh)
            val ox = (width - r.width.toFloat() * s) / 2
            val oy = (height + r.height.toFloat() * s) / 2
            fun x(v: Float) = ox + v * s
            fun y(v: Float) = oy - v * s
            val o = r.outline
            var i = 0
            while (i + 3 < o.size) {
                canvas.drawLine(x(o[i]), y(o[i + 1]), x(o[i + 2]), y(o[i + 3]), cut)
                i += 4
            }
            for ((n, b) in r.bends.withIndex()) {
                val l = b.line
                canvas.drawLine(x(l[0]), y(l[1]), x(l[2]), y(l[3]), bend)
                canvas.drawText("B${n + 1}", (x(l[0]) + x(l[2])) / 2, (y(l[1]) + y(l[3])) / 2 - 4 * d, label)
            }
        }
    }

    fun show(
        context: Context,
        r: Result,
        onKFactor: (Double) -> Unit,
        onSave: () -> Unit,
        onShare: () -> Unit,
    ) {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val secondary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666.toInt())
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
        }
        root.addView(PreviewView(context, r), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val steelKg = r.area * r.thickness * 7.85e-6
        val lines = ArrayList<String>()
        lines += context.getString(R.string.sheet_size, Holes.mm(r.width), Holes.mm(r.height))
        lines += context.getString(R.string.sheet_thickness, Holes.mm(r.thickness))
        lines += context.getString(R.string.sheet_area, fmt(r.area / 100.0, 2), fmt(steelKg, 3))
        root.addView(TextView(context).apply {
            text = lines.joinToString("\n")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(10), 0, dp(4))
        })

        root.addView(TextView(context).apply {
            text = context.getString(R.string.sheet_k_factor)
            setTextColor(secondary)
            setPadding(0, dp(6), 0, 0)
        })
        var dialog: androidx.appcompat.app.AlertDialog? = null
        val chips = ChipGroup(context).apply { isSingleSelection = true }
        for (k in K_FACTORS) {
            chips.addView(Chip(context).apply {
                text = fmt(k, 2)
                isCheckable = true
                isChecked = kotlin.math.abs(k - r.kFactor) < 1e-6
                setOnClickListener {
                    if (kotlin.math.abs(k - r.kFactor) > 1e-6) {
                        dialog?.dismiss()
                        onKFactor(k)
                    }
                }
            })
        }
        root.addView(chips)

        if (r.bends.isEmpty()) {
            root.addView(TextView(context).apply {
                text = context.getString(R.string.sheet_no_bends)
                setTextColor(secondary)
                setPadding(0, dp(6), 0, 0)
            })
        } else {
            root.addView(TextView(context).apply {
                text = context.getString(R.string.sheet_bends_title)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(2))
            })
            for ((n, b) in r.bends.withIndex()) {
                root.addView(TextView(context).apply {
                    text = context.getString(
                        R.string.sheet_bend_row, n + 1, fmt(b.angle, 1),
                        context.getString(if (b.up) R.string.sheet_up else R.string.sheet_down),
                        Holes.mm(b.innerRadius), Holes.mm(b.length), fmt(b.allowance, 2),
                    )
                    setPadding(0, dp(2), 0, dp(2))
                })
            }
        }
        if (r.skippedFaces > 0) {
            root.addView(TextView(context).apply {
                text = context.getString(R.string.sheet_skipped, r.skippedFaces)
                setTextColor(0xFFE53935.toInt())
                setPadding(0, dp(6), 0, 0)
            })
        }
        root.addView(TextView(context).apply {
            text = context.getString(R.string.sheet_note)
            setTextColor(secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, dp(8))
        })

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.sheet_title)
            .setView(ScrollView(context).apply { addView(root) })
            .setPositiveButton(R.string.sheet_save_dxf) { _, _ -> onSave() }
            .setNeutralButton(R.string.menu_share) { _, _ -> onShare() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
