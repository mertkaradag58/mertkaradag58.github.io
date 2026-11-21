package com.mertkaradag.stepviewer

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/**
 * ISO 286 geçme hesaplayıcı: anma ölçüsü + delik ve mil toleransı -> sınır ölçüler, boşluk/sıkılık
 * ve geçme türü.
 */
class FitDialog(private val context: Context, private val initialSize: Double? = null) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    fun show() {
        val prefs = context.getSharedPreferences("fit", Context.MODE_PRIVATE)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(4))
        }
        val secondary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666.toInt())

        fun label(text: String) = TextView(context).apply {
            this.text = text
            setTextColor(secondary)
            setPadding(0, dp(10), 0, dp(2))
        }

        root.addView(label(context.getString(R.string.fit_nominal)))
        val sizeEdit = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(initialSize?.let { Holes.mm(it) } ?: prefs.getString("size", "20"))
            setSelectAllOnFocus(true)
        }
        root.addView(sizeEdit)

        fun spinner(items: List<String>, selected: String): Spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(items.indexOf(selected).coerceAtLeast(0))
        }
        val grades = Iso286.GRADES.map { it.toString() }
        val holeLetter = spinner(Iso286.HOLE_LETTERS, prefs.getString("hl", "H")!!)
        val holeGrade = spinner(grades, prefs.getString("hg", "7")!!)
        val shaftLetter = spinner(Iso286.SHAFT_LETTERS, prefs.getString("sl", "g")!!)
        val shaftGrade = spinner(grades, prefs.getString("sg", "6")!!)

        fun row(title: String, a: Spinner, b: Spinner) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row(context.getString(R.string.fit_hole), holeLetter, holeGrade))
        root.addView(row(context.getString(R.string.fit_shaft), shaftLetter, shaftGrade))

        root.addView(label(context.getString(R.string.fit_common)))
        val chips = ChipGroup(context)
        for ((h, s, _) in Iso286.COMMON) {
            chips.addView(Chip(context).apply {
                text = "$h/$s"
                setOnClickListener {
                    holeLetter.setSelection(Iso286.HOLE_LETTERS.indexOf(h.takeWhile { c -> c.isLetter() }))
                    holeGrade.setSelection(grades.indexOf(h.dropWhile { c -> c.isLetter() }))
                    shaftLetter.setSelection(Iso286.SHAFT_LETTERS.indexOf(s.takeWhile { c -> c.isLetter() }))
                    shaftGrade.setSelection(grades.indexOf(s.dropWhile { c -> c.isLetter() }))
                }
            })
        }
        root.addView(chips)

        val result = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(12), 0, dp(8))
            setTextIsSelectable(true)
        }
        root.addView(result)

        fun update() {
            val size = parseNumber(sizeEdit.text.toString())
            val hc = holeLetter.selectedItem.toString() + holeGrade.selectedItem.toString()
            val sc = shaftLetter.selectedItem.toString() + shaftGrade.selectedItem.toString()
            if (size == null || size <= 0 || size > Iso286.MAX_SIZE) {
                result.text = context.getString(R.string.fit_range)
                return
            }
            val f = Iso286.fit(hc, sc, size)
            if (f == null) {
                result.text = context.getString(R.string.fit_range)
                return
            }
            val sz = Holes.mm(size)
            val lines = ArrayList<String>()
            lines += context.getString(
                R.string.fit_line_zone, context.getString(R.string.fit_hole), "Ø$sz ${f.hole.code}",
                dev(f.hole.upper), dev(f.hole.lower), mm3(size + f.hole.lower / 1000), mm3(size + f.hole.upper / 1000),
            )
            lines += context.getString(
                R.string.fit_line_zone, context.getString(R.string.fit_shaft), "Ø$sz ${f.shaft.code}",
                dev(f.shaft.upper), dev(f.shaft.lower), mm3(size + f.shaft.lower / 1000), mm3(size + f.shaft.upper / 1000),
            )
            lines += ""
            lines += context.getString(
                when (f.kind) {
                    Iso286.FitKind.CLEARANCE -> R.string.fit_kind_clearance
                    Iso286.FitKind.TRANSITION -> R.string.fit_kind_transition
                    Iso286.FitKind.INTERFERENCE -> R.string.fit_kind_interference
                },
            )
            if (f.maxClearance >= 0) lines += context.getString(R.string.fit_max_clearance, mm3(f.maxClearance / 1000))
            if (f.minClearance >= 0) lines += context.getString(R.string.fit_min_clearance, mm3(f.minClearance / 1000))
            if (f.minClearance < 0) lines += context.getString(R.string.fit_max_interference, mm3(-f.minClearance / 1000))
            if (f.maxClearance < 0) lines += context.getString(R.string.fit_min_interference, mm3(-f.maxClearance / 1000))
            Iso286.COMMON.firstOrNull { it.first == f.hole.code && it.second == f.shaft.code }?.let {
                lines += ""
                lines += context.getString(it.third)
            }
            result.text = lines.joinToString("\n")
            result.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            prefs.edit()
                .putString("size", sizeEdit.text.toString())
                .putString("hl", holeLetter.selectedItem.toString())
                .putString("hg", holeGrade.selectedItem.toString())
                .putString("sl", shaftLetter.selectedItem.toString())
                .putString("sg", shaftGrade.selectedItem.toString())
                .apply()
        }

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = update()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        listOf(holeLetter, holeGrade, shaftLetter, shaftGrade).forEach { it.onItemSelectedListener = listener }
        sizeEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = update()
        })
        update()

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.fit_title)
            .setView(ScrollView(context).apply { addView(root) })
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    companion object {
        /** Virgüllü veya noktalı ondalık sayı. */
        fun parseNumber(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

        /** µm sapmayı mm olarak işaretli yazar: +0,021 / 0 / -0,007. */
        fun dev(um: Double): String {
            if (kotlin.math.abs(um) < 1e-9) return "0"
            val s = String.format(Locale.getDefault(), "%.4f", kotlin.math.abs(um) / 1000).trimEnd('0')
            val t = if (s.endsWith(".") || s.endsWith(",")) s.dropLast(1) else s
            return (if (um > 0) "+" else "−") + t
        }

        fun mm3(v: Double): String = String.format(Locale.getDefault(), "%.3f", v)
    }
}
