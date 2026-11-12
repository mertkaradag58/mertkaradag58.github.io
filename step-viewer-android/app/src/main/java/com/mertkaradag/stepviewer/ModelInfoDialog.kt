package com.mertkaradag.stepviewer

import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Model bilgisi: boyutlar, sayılar, hacim, alan, ağırlık merkezi ve seçilen malzemeye göre kütle.
 * Gizli parçalar hesaba katılmaz (bir parçayı "yalnız göster" ile seçip bilgisini alabilirsiniz).
 */
class ModelInfoDialog(
    private val context: Context,
    private val mesh: StepMesh,
    private val partVisible: BooleanArray,
    private val runInBackground: (() -> Unit) -> Unit,
    private val runOnUi: (() -> Unit) -> Unit,
    /** Tek parça için: başlık (kapsam satırı) ve parça indeksi. */
    private val partTitle: String? = null,
    private val part: Int = -1,
) {
    private var volumeMm3 = 0.0
    private var hasSolids = false
    private var propsReady = false

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_model_info, null)
        val scope = view.findViewById<TextView>(R.id.info_scope)
        val general = view.findViewById<TextView>(R.id.info_general)
        val props = view.findViewById<TextView>(R.id.info_props)
        val material = view.findViewById<AutoCompleteTextView>(R.id.info_material)
        val densityLayout = view.findViewById<TextInputLayout>(R.id.info_density_layout)
        val densityEdit = view.findViewById<TextInputEditText>(R.id.info_density)
        val massText = view.findViewById<TextView>(R.id.info_mass)

        val visibleParts = partVisible.indices.filter { partVisible[it] }
        val allVisible = visibleParts.size == mesh.partCount
        scope.text = if (partTitle != null) {
            partTitle
        } else if (allVisible) {
            context.getString(R.string.info_scope_all, mesh.partCount)
        } else {
            context.getString(R.string.info_scope_visible, visibleParts.size, mesh.partCount)
        }

        val b = mesh.bbox
        // OBJ, STL, 3DS ve PLY dosyalarında birim yoktur (glTF/DAE/FBX'te birim mm'ye çevrilir).
        val unitless = mesh.format == StepMesh.FORMAT_OBJ || mesh.format == StepMesh.FORMAT_STL ||
            mesh.format == StepMesh.FORMAT_3DS || mesh.format == StepMesh.FORMAT_PLY ||
            mesh.format == StepMesh.FORMAT_OFF
        general.text = listOfNotNull(
            context.getString(R.string.info_format, mesh.formatName),
            Units.localize(context.getString(R.string.info_size, Units.fl(b[3] - b[0]), Units.fl(b[4] - b[1]), Units.fl(b[5] - b[2]))),
            context.getString(R.string.info_counts, mesh.partCount, mesh.faceCount, mesh.triangleCount),
            if (unitless) context.getString(R.string.info_mesh_units) else null,
        ).joinToString("\n")
        if (part >= 0) {
            val pb = partBounds(part)
            val faces = mesh.partFaceOffsets[part + 1] - mesh.partFaceOffsets[part]
            val tris = mesh.partTriangleRange(part).let { it.last - it.first + 1 }
            general.text = listOf(
                Units.localize(context.getString(R.string.info_size, Units.fl(pb[3] - pb[0]), Units.fl(pb[4] - pb[1]), Units.fl(pb[5] - pb[2]))),
                context.getString(R.string.info_part_counts, faces, tris),
            ).joinToString("\n")
        }
        props.text = context.getString(R.string.measure_busy)

        val names = context.resources.getStringArray(R.array.material_names)
        val densities = context.resources.getStringArray(R.array.material_densities).map { it.toDouble() }
        material.setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, names))
        material.setText(names[0], false)
        var density = densities[0]

        fun updateMass() {
            massText.text = when {
                !propsReady -> ""
                !hasSolids -> context.getString(
                    if (mesh.isMeshFormat) R.string.info_no_solid_mesh else R.string.info_no_solid,
                )
                else -> {
                    val grams = volumeMm3 / 1000.0 * density
                    val textMass = if (grams >= 1000.0) "${fmt(grams / 1000.0, 3)} kg" else "${fmt(grams, 2)} g"
                    context.getString(R.string.info_mass, textMass)
                }
            }
        }

        material.setOnItemClickListener { _, _, position, _ ->
            val custom = densities[position] <= 0.0
            densityLayout.visibility = if (custom) android.view.View.VISIBLE else android.view.View.GONE
            density = if (custom) densityEdit.text?.toString()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
            else densities[position]
            updateMass()
        }
        densityEdit.doAfterTextChanged {
            density = it?.toString()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
            updateMass()
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(if (partTitle != null) R.string.part_info_title else R.string.info_title)
            .setView(view)
            .setPositiveButton(R.string.ok, null)
            .show()

        val ids = if (allVisible) null else visibleParts.toIntArray()
        runInBackground {
            val p = if (visibleParts.isEmpty()) null else StepNative.massProps(ids)
            runOnUi {
                propsReady = true
                if (p == null) {
                    hasSolids = false
                    props.text = context.getString(R.string.info_no_solid)
                } else {
                    volumeMm3 = p[0]
                    hasSolids = p[5] > 0.5
                    val lines = mutableListOf<String>()
                    if (hasSolids) lines += context.getString(R.string.info_volume, fmt(p[0] / 1000.0, 3))
                    lines += context.getString(R.string.info_area, fmt(p[1] / 100.0, 3))
                    lines += Units.localize(context.getString(R.string.info_center, Units.fl(p[2]), Units.fl(p[3]), Units.fl(p[4])))
                    props.text = lines.joinToString("\n")
                }
                updateMass()
            }
        }
    }

    /** Parçanın üçgen köşelerinden eksen hizalı sınır kutusu. */
    private fun partBounds(p: Int): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        val v = mesh.vertices
        val k = StepMesh.FLOATS_PER_VERTEX
        for (t in mesh.partTriangleRange(p)) {
            for (c in 0 until 3) {
                val o = mesh.indices[t * 3 + c] * k
                for (a in 0 until 3) {
                    if (v[o + a] < b[a]) b[a] = v[o + a]
                    if (v[o + a] > b[a + 3]) b[a + 3] = v[o + a]
                }
            }
        }
        if (b[0] > b[3]) return FloatArray(6)
        return b
    }
}
