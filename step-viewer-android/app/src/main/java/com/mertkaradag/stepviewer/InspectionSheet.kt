package com.mertkaradag.stepviewer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Kalite kontrol raporu listesi: ölçülen değerler satırda girilir, sonuç anında görünür. */
class InspectionSheet(
    private val context: Context,
    private val report: Inspection,
    private val onSavePdf: () -> Unit,
    private val onSharePdf: () -> Unit,
    private val onSelect: (Inspection.Item) -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private lateinit var list: LinearLayout
    private lateinit var summary: TextView

    fun show() {
        val dialog = BottomSheetDialog(context)
        val secondary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666.toInt())

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = context.getString(R.string.qc_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "PDF"
            setIconResource(R.drawable.ic_download)
            setOnClickListener {
                report.save()
                dialog.dismiss()
                onSavePdf()
            }
        })
        header.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setIconResource(R.drawable.ic_share)
            iconPadding = 0
            contentDescription = context.getString(R.string.menu_share)
            setOnClickListener {
                report.save()
                dialog.dismiss()
                onSharePdf()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        root.addView(header)

        // Rapor bilgileri
        fun field(hint: String, value: String, onChange: (String) -> Unit) = EditText(context).apply {
            this.hint = hint
            setText(value)
            setSingleLine()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            addTextChangedListener(simpleWatcher { onChange(it) })
        }
        val infoRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        infoRow.addView(field(context.getString(R.string.qc_inspector), report.inspector) { report.inspector = it },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        infoRow.addView(field(context.getString(R.string.qc_order), report.order) { report.order = it },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        root.addView(infoRow)

        val generalRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        generalRow.addView(TextView(context).apply {
            text = context.getString(R.string.qc_general)
            setTextColor(secondary)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val classes = Iso286.GENERAL_CLASSES.map { "ISO 2768-$it" }
        generalRow.addView(Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, classes)
            setSelection(Iso286.GENERAL_CLASSES.indexOf(report.generalClass).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    report.generalClass = Iso286.GENERAL_CLASSES[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        })
        root.addView(generalRow)

        summary = TextView(context).apply {
            setTextColor(secondary)
            setPadding(0, dp(4), 0, dp(6))
        }
        root.addView(summary)

        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(NestedScrollView(context).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        bottom.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = context.getString(R.string.qc_clear)
            isEnabled = report.items.isNotEmpty()
            setOnClickListener {
                MaterialAlertDialogBuilder(context)
                    .setMessage(R.string.qc_clear_confirm)
                    .setPositiveButton(R.string.qc_clear) { _, _ ->
                        report.items.clear()
                        report.save()
                        rebuild()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        })
        bottom.addView(MaterialButton(context).apply {
            text = context.getString(R.string.qc_add_manual)
            setOnClickListener {
                val item = report.newItem(context.getString(R.string.qc_item_default), 10.0, Inspection.Dim.MM, null)
                editItem(context, report, item, isNew = true) {
                    report.items += item
                    report.save()
                    rebuild()
                }
            }
        })
        root.addView(bottom)

        rebuild()
        dialog.setContentView(root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener { report.save() }
        dialog.show()
    }

    private fun updateSummary() {
        val (ok, nok, open) = report.counts()
        summary.text = if (report.items.isEmpty()) {
            context.getString(R.string.qc_empty)
        } else {
            context.getString(R.string.qc_summary, report.items.size, ok, nok, open)
        }
    }

    private fun rebuild() {
        list.removeAllViews()
        val secondary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666.toInt())
        val divider = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0x22000000)
        for ((idx, item) in report.items.withIndex()) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val badge = TextView(context).apply {
                text = (idx + 1).toString()
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            }
            fun paintBadge() {
                (badge.background as GradientDrawable).setColor(statusColor(item.ok))
            }
            paintBadge()
            row.addView(badge, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) })

            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val tv = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener {
                    editItem(context, report, item, isNew = false, onDelete = {
                        report.items.remove(item)
                        report.save()
                        rebuild()
                    }) {
                        report.save()
                        rebuild()
                    }
                }
                setOnLongClickListener {
                    onSelect(item)
                    true
                }
            }
            texts.addView(TextView(context).apply {
                text = item.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            texts.addView(TextView(context).apply {
                text = report.nominalText(item) + "  " + report.toleranceText(item) + "\n" +
                    report.value(item.min, item.unit) + " … " + report.value(item.max, item.unit)
                setTextColor(secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val actual = EditText(context).apply {
                hint = context.getString(R.string.qc_actual_hint)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                setSingleLine()
                gravity = Gravity.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                item.actual?.let { setText(report.value(it, Inspection.Dim.MM).trimEnd('0').trimEnd(',', '.')) }
                addTextChangedListener(simpleWatcher { s ->
                    item.actual = FitDialog.parseNumber(s)
                    paintBadge()
                    updateSummary()
                })
            }
            row.addView(actual, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            list.addView(row)
            list.addView(View(context).apply { setBackgroundColor(divider) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }
        updateSummary()
    }

    companion object {
        fun statusColor(ok: Boolean?): Int = when (ok) {
            true -> Color.rgb(46, 160, 67)
            false -> Color.rgb(214, 48, 49)
            null -> Color.rgb(150, 150, 150)
        }

        fun simpleWatcher(onChange: (String) -> Unit) = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = onChange(s?.toString() ?: "")
        }

        /**
         * Madde düzenleme: ad, nominal, ISO 286 kodu (girilirse sapmalar otomatik), üst/alt sapma,
         * ölçülen değer.
         */
        fun editItem(
            context: Context,
            report: Inspection,
            item: Inspection.Item,
            isNew: Boolean,
            onDelete: (() -> Unit)? = null,
            onSaved: () -> Unit,
        ) {
            val d = context.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(6), dp(22), 0)
            }
            val secondary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666.toInt())
            fun label(t: String) = TextView(context).apply {
                text = t
                setTextColor(secondary)
                setPadding(0, dp(8), 0, 0)
            }
            fun num(v: Double?, signed: Boolean = true) = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
                setSingleLine()
                if (v != null) setText(plain(v))
            }
            val isMm = item.unit == Inspection.Dim.MM
            val unitSuffix = if (isMm) " (mm)" else " (°)"

            root.addView(label(context.getString(R.string.qc_f_label)))
            val labelEdit = EditText(context).apply {
                setText(item.label)
                setSingleLine()
            }
            root.addView(labelEdit)
            root.addView(label(context.getString(R.string.qc_f_nominal) + unitSuffix))
            val nominal = num(item.nominal)
            root.addView(nominal)

            val fitEdit = EditText(context).apply {
                setText(item.fit)
                setSingleLine()
                hint = "H7, g6, js8…"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if (isMm) {
                root.addView(label(context.getString(R.string.qc_f_fit)))
                root.addView(fitEdit)
            }
            val tolRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val upperCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val lowerCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val upper = num(item.upper)
            val lower = num(item.lower)
            upperCol.addView(label(context.getString(R.string.qc_f_upper)))
            upperCol.addView(upper)
            lowerCol.addView(label(context.getString(R.string.qc_f_lower)))
            lowerCol.addView(lower)
            tolRow.addView(upperCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tolRow.addView(lowerCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
            root.addView(tolRow)
            val fitInfo = TextView(context).apply {
                setTextColor(secondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            root.addView(fitInfo)
            root.addView(label(context.getString(R.string.qc_f_actual) + unitSuffix))
            val actual = num(item.actual)
            root.addView(actual)

            fun applyFit() {
                val code = fitEdit.text.toString().trim()
                if (code.isEmpty()) {
                    fitInfo.text = ""
                    return
                }
                val n = FitDialog.parseNumber(nominal.text.toString())
                val z = if (n != null) Iso286.zone(code, n) else null
                if (z == null) {
                    fitInfo.text = context.getString(R.string.fit_range)
                } else {
                    upper.setText(plain(z.upper / 1000))
                    lower.setText(plain(z.lower / 1000))
                    fitInfo.text = context.getString(
                        if (z.isHole) R.string.qc_fit_hole else R.string.qc_fit_shaft, z.code,
                    )
                }
            }
            fitEdit.addTextChangedListener(simpleWatcher { applyFit() })
            nominal.addTextChangedListener(simpleWatcher { if (fitEdit.text.isNotBlank()) applyFit() })

            val b = MaterialAlertDialogBuilder(context)
                .setTitle(if (isNew) R.string.qc_add_title else R.string.qc_edit_title)
                .setView(ScrollView(context).apply { addView(root) })
                .setPositiveButton(R.string.markup_save) { _, _ ->
                    item.label = labelEdit.text.toString().ifBlank { item.label }
                    FitDialog.parseNumber(nominal.text.toString())?.let { item.nominal = it }
                    item.upper = FitDialog.parseNumber(upper.text.toString()) ?: item.upper
                    item.lower = FitDialog.parseNumber(lower.text.toString()) ?: item.lower
                    if (item.lower > item.upper) {
                        val t = item.lower
                        item.lower = item.upper
                        item.upper = t
                    }
                    val code = fitEdit.text.toString().trim()
                    item.fit = if (Iso286.zone(code, item.nominal) != null) Iso286.zone(code, item.nominal)!!.code else ""
                    item.actual = FitDialog.parseNumber(actual.text.toString())
                    onSaved()
                }
                .setNegativeButton(R.string.cancel, null)
            if (onDelete != null) b.setNeutralButton(R.string.qc_delete) { _, _ -> onDelete() }
            b.show()
        }

        /** Gereksiz sıfırsız, nokta ondalıklı (giriş alanları için). */
        fun plain(v: Double): String {
            val s = String.format(java.util.Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
            return if (s == "-0") "0" else s
        }
    }
}
