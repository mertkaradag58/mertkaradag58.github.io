package com.mertkaradag.stepviewer

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Currency
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Maliyet hesabı: açık modelin (görünen parçalar ya da seçim) parça başına ve toplam maliyeti.
 *
 * Her parça ayrı hesaplanır: parçanın sınır kutusundan ham stok (blok ya da yuvarlak çubuk,
 * işleme payıyla), stok kütlesi × malzeme fiyatı, talaş hacmi + yüzey alanı + delik sayısından
 * tahmini işleme süresi × makine saat ücreti. Üstüne hazırlık (seri başına), ek işlemler,
 * genel gider, kâr ve KDV eklenir. Fiyatlar ve oranlar cihazda saklanır, kullanıcı düzenler.
 */
class CostDialog(
    private val context: Context,
    private val mesh: StepMesh,
    /** Hesaba girecek parçalar. */
    private val parts: IntArray,
    private val scopeTitle: String,
    private val runInBackground: (() -> Unit) -> Unit,
    private val runOnUi: (() -> Unit) -> Unit,
    /** Delik listesi (arka planda çağrılır); ağ dosyalarında null. */
    private val loadHoles: () -> List<Holes.Hole>?,
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Parça verisi (arka planda hesaplanır). */
    private class PartData(
        val dims: DoubleArray, // sınır kutusu x, y, z (mm), küçükten büyüğe sıralı
        val volumeMm3: Double, // katı yoksa NaN
        val areaMm2: Double,
        var holes: Int,
    )

    private var data: List<PartData>? = null

    // Girdiler
    private val materialNames = context.resources.getStringArray(R.array.material_names)
    private val densities = context.resources.getStringArray(R.array.material_densities).map { it.toDouble() }
    private val currencyCode: String = try {
        Currency.getInstance(Locale.getDefault()).currencyCode
    } catch (_: Exception) {
        "USD"
    }
    private val useTry = currencyCode == "TRY"
    private val defaultPrices = if (useTry) PRICES_TRY else PRICES_USD
    private var materialIndex = prefs.getInt("material", 0).coerceIn(0, materialNames.size - 1)
    private var stockMode = prefs.getInt("stock_mode", STOCK_BLOCK).coerceIn(0, 2)
    private var timeEdited = false

    private lateinit var scopeText: TextView
    private lateinit var partText: TextView
    private lateinit var stockText: TextView
    private lateinit var resultText: TextView
    private lateinit var totalText: TextView
    private lateinit var densityLayout: TextInputLayout
    private lateinit var densityEdit: TextInputEditText
    private lateinit var priceEdit: TextInputEditText
    private lateinit var priceLayout: TextInputLayout
    private lateinit var allowanceLayout: TextInputLayout
    private lateinit var allowanceEdit: TextInputEditText
    private lateinit var rateEdit: TextInputEditText
    private lateinit var rateLayout: TextInputLayout
    private lateinit var timeEdit: TextInputEditText
    private lateinit var timeLayout: TextInputLayout
    private lateinit var setupEdit: TextInputEditText
    private lateinit var qtyEdit: TextInputEditText
    private lateinit var extraEdit: TextInputEditText
    private lateinit var extraLayout: TextInputLayout
    private lateinit var overheadEdit: TextInputEditText
    private lateinit var profitEdit: TextInputEditText
    private lateinit var vatEdit: TextInputEditText
    private var shareText = ""
    private var updating = false

    fun show() {
        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        scroll.addView(root)

        scopeText = label(root, scopeTitle, accent = true)
        partText = body(root)
        partText.text = context.getString(R.string.measure_busy)

        // Malzeme
        header(root, R.string.cost_section_material)
        val materialLayout = dropdownLayout(root, R.string.info_material)
        val material = materialLayout.editText as MaterialAutoCompleteTextView
        material.setSimpleItems(materialNames)
        material.setText(materialNames[materialIndex], false)
        densityLayout = field(root, R.string.info_density, prefs.getString("custom_density", "") ?: "")
        densityEdit = densityLayout.editText as TextInputEditText
        priceLayout = field(root, 0, priceFor(materialIndex))
        priceEdit = priceLayout.editText as TextInputEditText
        priceLayout.hint = context.getString(R.string.cost_price_per_kg, currencySymbol())

        // Ham malzeme
        header(root, R.string.cost_section_stock)
        val stockNames = context.resources.getStringArray(R.array.cost_stock_modes)
        val stockLayout = dropdownLayout(root, R.string.cost_stock_type)
        val stock = stockLayout.editText as MaterialAutoCompleteTextView
        stock.setSimpleItems(stockNames)
        stock.setText(stockNames[stockMode], false)
        allowanceLayout = field(root, R.string.cost_allowance, prefs.getString("allowance", "3") ?: "3")
        allowanceEdit = allowanceLayout.editText as TextInputEditText
        stockText = body(root)

        // İşçilik
        header(root, R.string.cost_section_labor)
        rateLayout = field(root, 0, rateFor(stockMode))
        rateEdit = rateLayout.editText as TextInputEditText
        rateLayout.hint = context.getString(R.string.cost_rate, currencySymbol())
        timeLayout = field(root, R.string.cost_time, "")
        timeEdit = timeLayout.editText as TextInputEditText
        setupEdit = field(root, R.string.cost_setup, prefs.getString("setup", "30") ?: "30").editText as TextInputEditText
        qtyEdit = field(root, R.string.cost_quantity, prefs.getString("qty", "1") ?: "1").editText as TextInputEditText

        // Ek giderler ve kâr
        header(root, R.string.cost_section_extra)
        extraLayout = field(root, 0, prefs.getString("extra", "0") ?: "0")
        extraEdit = extraLayout.editText as TextInputEditText
        extraLayout.hint = context.getString(R.string.cost_extra, currencySymbol())
        overheadEdit = field(root, R.string.cost_overhead, prefs.getString("overhead", "15") ?: "15").editText as TextInputEditText
        profitEdit = field(root, R.string.cost_profit, prefs.getString("profit", "20") ?: "20").editText as TextInputEditText
        vatEdit = field(root, R.string.cost_vat, prefs.getString("vat", if (useTry) "20" else "0") ?: "0").editText as TextInputEditText

        // Sonuç
        header(root, R.string.cost_section_result)
        resultText = body(root)
        totalText = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.measure))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(totalText)
        label(root, context.getString(R.string.cost_disclaimer), accent = false).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        }

        refreshModeViews()

        material.setOnItemClickListener { _, _, position, _ ->
            materialIndex = position
            prefs.edit().putInt("material", position).apply()
            updating = true
            priceEdit.setText(priceFor(position))
            updating = false
            refreshModeViews()
            recalc()
        }
        stock.setOnItemClickListener { _, _, position, _ ->
            stockMode = position
            prefs.edit().putInt("stock_mode", position).apply()
            updating = true
            rateEdit.setText(rateFor(position))
            updating = false
            refreshModeViews()
            recalc()
        }
        densityEdit.doAfterTextChanged { if (!updating) { prefs.edit().putString("custom_density", it.toString()).apply(); recalc() } }
        priceEdit.doAfterTextChanged {
            if (!updating) {
                prefs.edit().putString("price_$materialIndex", it.toString()).apply()
                recalc()
            }
        }
        rateEdit.doAfterTextChanged {
            if (!updating) {
                prefs.edit().putString("rate_$stockMode", it.toString()).apply()
                recalc()
            }
        }
        timeEdit.doAfterTextChanged {
            if (!updating) {
                timeEdited = !it.isNullOrBlank()
                recalc()
            }
        }
        persistOnChange(allowanceEdit, "allowance")
        persistOnChange(setupEdit, "setup")
        persistOnChange(qtyEdit, "qty")
        persistOnChange(extraEdit, "extra")
        persistOnChange(overheadEdit, "overhead")
        persistOnChange(profitEdit, "profit")
        persistOnChange(vatEdit, "vat")

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.cost_title)
            .setView(scroll)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.menu_share) { _, _ -> share() }
            .show()

        runInBackground {
            val result = computePartData()
            runOnUi {
                data = result
                recalc()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Hesap
    // -----------------------------------------------------------------------

    private fun computePartData(): List<PartData> {
        val out = ArrayList<PartData>(parts.size)
        val single = parts.size > MAX_PER_PART
        if (single) {
            // Çok parçalı büyük montaj: tek bir gövde gibi hesapla (hızlı yol).
            val b = boundsOf(parts.asList())
            val p = try { StepNative.massProps(parts) } catch (_: Throwable) { null }
            out += PartData(sortedDims(b), solidVolume(p), p?.get(1) ?: 0.0, 0)
        } else {
            for (part in parts) {
                val b = boundsOf(listOf(part))
                val p = try { StepNative.massProps(intArrayOf(part)) } catch (_: Throwable) { null }
                out += PartData(sortedDims(b), solidVolume(p), p?.get(1) ?: 0.0, 0)
            }
        }
        val holes = if (mesh.isMeshFormat) null else try { loadHoles() } catch (_: Throwable) { null }
        if (holes != null) {
            if (single) {
                val set = parts.toHashSet()
                out[0].holes = holes.count { it.part in set }
            } else {
                val index = HashMap<Int, Int>()
                parts.forEachIndexed { i, p -> index[p] = i }
                for (h in holes) index[h.part]?.let { out[it].holes++ }
            }
        }
        return out
    }

    private fun solidVolume(p: DoubleArray?): Double =
        if (p != null && p[5] > 0.5 && p[0] > 0.0) p[0] else Double.NaN

    private fun sortedDims(b: FloatArray): DoubleArray =
        doubleArrayOf((b[3] - b[0]).toDouble(), (b[4] - b[1]).toDouble(), (b[5] - b[2]).toDouble()).also { it.sort() }

    private fun boundsOf(list: List<Int>): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        val v = mesh.vertices
        val k = StepMesh.FLOATS_PER_VERTEX
        for (p in list) {
            for (t in mesh.partTriangleRange(p)) {
                for (c in 0 until 3) {
                    val o = mesh.indices[t * 3 + c] * k
                    for (a in 0 until 3) {
                        if (v[o + a] < b[a]) b[a] = v[o + a]
                        if (v[o + a] > b[a + 3]) b[a + 3] = v[o + a]
                    }
                }
            }
        }
        if (b[0] > b[3]) return FloatArray(6)
        return b
    }

    private fun num(e: TextInputEditText, fallback: Double = 0.0): Double =
        e.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: fallback

    private fun densityGcm3(): Double {
        val d = densities[materialIndex]
        return if (d > 0.0) d else num(densityEdit)
    }

    private fun recalc() {
        val list = data ?: return
        val dens = densityGcm3() // g/cm³ = kg/dm³ ; kg = mm³ * dens / 1e6
        val price = num(priceEdit)
        val allowance = max(0.0, num(allowanceEdit))
        val rate = num(rateEdit)
        val setupMin = max(0.0, num(setupEdit))
        val qty = max(1.0, ceil(num(qtyEdit, 1.0)))
        val extra = num(extraEdit)
        val overheadPct = num(overheadEdit)
        val profitPct = num(profitEdit)
        val vatPct = num(vatEdit)

        var partVol = 0.0
        var partArea = 0.0
        var stockVol = 0.0
        var holes = 0
        var missingSolid = 0
        var estMin = 0.0
        val mrr = MRR[materialIndex.coerceIn(0, MRR.size - 1)] * 1000.0 // mm³/dk
        for (d in list) {
            val sv = stockVolume(d.dims, allowance)
            val pv = if (d.volumeMm3.isNaN()) {
                missingSolid++
                0.0
            } else d.volumeMm3
            partVol += pv
            partArea += d.areaMm2
            holes += d.holes
            val usedStock = if (stockMode == STOCK_NET) pv else sv
            stockVol += usedStock
            estMin += when (stockMode) {
                STOCK_NET -> pv / 1000.0 / PRINT_RATE_CM3_MIN
                else -> {
                    val removed = max(0.0, sv - pv)
                    removed / mrr + d.areaMm2 / (mrr * FINISH_FACTOR) + d.holes * HOLE_MIN + HANDLING_MIN
                }
            }
        }

        val partKg = partVol * dens / 1e6
        val stockKg = stockVol * dens / 1e6
        val scrapKg = max(0.0, stockKg - partKg)

        // Süre: kullanıcı yazmadıysa tahmini süre kullanılır (alanın altında gösterilir).
        timeLayout.helperText = context.getString(R.string.cost_time_estimate, fmt(estMin, 1))
        val minutes = if (timeEdited) num(timeEdit) else estMin

        val sym = currencySymbol()
        fun money(x: Double) = "${fmt(x, 2)} $sym"
        fun kg(x: Double) = if (x >= 1.0) "${fmt(x, 3)} kg" else "${fmt(x * 1000.0, 1)} g"

        // Parça özeti
        val partLines = mutableListOf<String>()
        if (list.size == 1 && parts.size == 1) {
            val d = list[0].dims
            partLines += context.getString(R.string.info_size, fmt(d[2], 1), fmt(d[1], 1), fmt(d[0], 1))
        } else {
            partLines += context.getString(R.string.cost_parts_count, parts.size)
        }
        if (missingSolid < list.size) {
            partLines += context.getString(R.string.info_volume, fmt(partVol / 1000.0, 3))
            partLines += context.getString(R.string.cost_part_mass, kg(partKg))
        }
        if (missingSolid > 0) partLines += context.getString(R.string.cost_missing_solid, missingSolid)
        if (!mesh.isMeshFormat) partLines += context.getString(R.string.cost_holes, holes)
        partText.text = partLines.joinToString("\n")

        // Stok özeti
        stockText.text = if (stockMode == STOCK_NET) {
            context.getString(R.string.cost_stock_net_info)
        } else {
            val lines = mutableListOf<String>()
            if (list.size == 1) lines += stockDimsText(list[0].dims, allowance)
            lines += context.getString(R.string.cost_stock_mass, kg(stockKg))
            if (stockKg > 0.0 && missingSolid < list.size) {
                lines += context.getString(R.string.cost_scrap, kg(scrapKg), fmt(scrapKg / stockKg * 100.0, 0))
            }
            lines.joinToString("\n")
        }

        // Maliyet (parça başına)
        val materialCost = stockKg * price
        val machining = minutes / 60.0 * rate
        val setupShare = setupMin / 60.0 * rate / qty
        val subtotal = materialCost + machining + setupShare + extra
        val overhead = subtotal * overheadPct / 100.0
        val profit = (subtotal + overhead) * profitPct / 100.0
        val unit = subtotal + overhead + profit
        val vat = unit * vatPct / 100.0
        val unitWithVat = unit + vat

        val lines = listOf(
            context.getString(R.string.cost_line_material, money(materialCost)),
            context.getString(R.string.cost_line_machining, fmt(minutes, 1), money(machining)),
            context.getString(R.string.cost_line_setup, money(setupShare)),
            context.getString(R.string.cost_line_extra, money(extra)),
            context.getString(R.string.cost_line_overhead, fmt(overheadPct, 0), money(overhead)),
            context.getString(R.string.cost_line_profit, fmt(profitPct, 0), money(profit)),
            context.getString(R.string.cost_line_unit, money(unit)),
            context.getString(R.string.cost_line_vat, fmt(vatPct, 0), money(vat)),
        )
        resultText.text = lines.joinToString("\n")
        val qtyInt = qty.toInt()
        totalText.text = context.getString(R.string.cost_total, money(unitWithVat), qtyInt, money(unitWithVat * qty))

        shareText = buildString {
            appendLine(context.getString(R.string.cost_title) + " · " + scopeTitle)
            appendLine(partText.text)
            appendLine(context.getString(R.string.info_material) + ": " + materialNames[materialIndex] +
                " · " + money(price) + "/kg")
            appendLine(stockText.text)
            appendLine(lines.joinToString("\n"))
            appendLine(totalText.text)
        }
    }

    private fun stockVolume(d: DoubleArray, allowance: Double): Double {
        val a = 2.0 * allowance
        return when (stockMode) {
            STOCK_ROUND -> {
                val dia = roundUp(sqrt(d[0] * d[0] + d[1] * d[1]) + a)
                Math.PI * dia * dia / 4.0 * (d[2] + a)
            }
            else -> (d[0] + a) * (d[1] + a) * (d[2] + a)
        }
    }

    private fun stockDimsText(d: DoubleArray, allowance: Double): String {
        val a = 2.0 * allowance
        return if (stockMode == STOCK_ROUND) {
            context.getString(R.string.cost_stock_round_dims, fmt(roundUp(sqrt(d[0] * d[0] + d[1] * d[1]) + a), 0), fmt(d[2] + a, 1))
        } else {
            context.getString(R.string.cost_stock_block_dims, fmt(d[2] + a, 1), fmt(d[1] + a, 1), fmt(d[0] + a, 1))
        }
    }

    /** Çubuk çapını bir üst 1 mm'ye (20 mm'den büyükse 5 mm'ye) yuvarlar. */
    private fun roundUp(dia: Double): Double = if (dia <= 20.0) ceil(dia) else ceil(dia / 5.0) * 5.0

    private fun refreshModeViews() {
        densityLayout.visibility = if (densities[materialIndex] <= 0.0) View.VISIBLE else View.GONE
        allowanceLayout.visibility = if (stockMode == STOCK_NET) View.GONE else View.VISIBLE
    }

    private fun priceFor(i: Int): String =
        prefs.getString("price_$i", null) ?: trim(defaultPrices.getOrElse(i) { 0.0 })

    private fun rateFor(mode: Int): String =
        prefs.getString("rate_$mode", null) ?: trim(
            if (mode == STOCK_NET) (if (useTry) 150.0 else 5.0) else (if (useTry) 1500.0 else 60.0),
        )

    private fun trim(x: Double): String = if (x == Math.floor(x)) x.toLong().toString() else x.toString()

    private fun currencySymbol(): String = when (currencyCode) {
        "TRY" -> "₺"
        else -> try {
            Currency.getInstance(currencyCode).getSymbol(Locale.getDefault())
        } catch (_: Exception) {
            currencyCode
        }
    }

    private fun persistOnChange(e: TextInputEditText, key: String) {
        e.doAfterTextChanged {
            if (!updating) {
                prefs.edit().putString(key, it.toString()).apply()
                recalc()
            }
        }
    }

    private fun share() {
        if (shareText.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.cost_title))
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.menu_share)))
    }

    // -----------------------------------------------------------------------
    // Görünüm yardımcıları
    // -----------------------------------------------------------------------

    private fun label(root: LinearLayout, text: String, accent: Boolean): TextView =
        TextView(context).apply {
            this.text = text
            textSize = if (accent) 15f else 14f
            setTextColor(ContextCompat.getColor(context, if (accent) R.color.accent else R.color.text))
            if (accent) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
            root.addView(this)
        }

    private fun body(root: LinearLayout): TextView = TextView(context).apply {
        textSize = 14f
        setLineSpacing(dp(3).toFloat(), 1f)
        setTextColor(ContextCompat.getColor(context, R.color.text))
        setTextIsSelectable(true)
        setPadding(0, dp(4), 0, dp(4))
        root.addView(this)
    }

    private fun header(root: LinearLayout, res: Int) {
        TextView(context).apply {
            text = context.getString(res)
            textSize = 13f
            isAllCaps = true
            letterSpacing = 0.06f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.measure))
            setPadding(0, dp(16), 0, dp(4))
            root.addView(this)
        }
    }

    private fun field(root: LinearLayout, hint: Int, value: String): TextInputLayout {
        val layout = TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        if (hint != 0) layout.hint = context.getString(hint)
        val edit = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(value)
        }
        layout.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(
            layout,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            },
        )
        return layout
    }

    private fun dropdownLayout(root: LinearLayout, hint: Int): TextInputLayout {
        val layout = TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle)
        layout.hint = context.getString(hint)
        val auto = MaterialAutoCompleteTextView(layout.context).apply {
            inputType = InputType.TYPE_NULL
        }
        layout.addView(auto, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(
            layout,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            },
        )
        return layout
    }

    companion object {
        private const val PREFS = "cost"
        const val STOCK_BLOCK = 0
        const val STOCK_ROUND = 1
        const val STOCK_NET = 2

        /** Bu sayıdan çok parçada montaj tek gövde gibi hesaplanır. */
        private const val MAX_PER_PART = 300

        /** Varsayılan malzeme fiyatları (material_names sırasıyla, kg başına). */
        private val PRICES_TRY = doubleArrayOf(45.0, 180.0, 40.0, 170.0, 380.0, 480.0, 1600.0, 120.0, 600.0, 260.0, 0.0)
        private val PRICES_USD = doubleArrayOf(1.2, 4.5, 1.0, 4.0, 9.0, 11.0, 35.0, 3.0, 20.0, 6.0, 0.0)

        /** Kaba işleme talaş kaldırma hızı, cm³/dk (malzeme sırasıyla). */
        private val MRR = doubleArrayOf(12.0, 6.0, 15.0, 50.0, 30.0, 20.0, 3.0, 60.0, 60.0, 60.0, 12.0)

        /** Finiş: dakikada (MRR × bu katsayı) mm² yüzey (MRR mm³/dk cinsinden). */
        private const val FINISH_FACTOR = 0.5
        private const val HOLE_MIN = 0.5
        private const val HANDLING_MIN = 2.0

        /** 3D baskı hızı (FDM, ~15 cm³/saat). */
        private const val PRINT_RATE_CM3_MIN = 0.25
    }
}
