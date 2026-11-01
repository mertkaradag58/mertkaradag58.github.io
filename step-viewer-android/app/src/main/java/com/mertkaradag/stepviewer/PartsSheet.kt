package com.mertkaradag.stepviewer

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Space
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

/**
 * Montaj ağacı: parçaları göster/gizle, yalnız birini göster, dokununca vurgula.
 * Görünürlük durumu [partVisible] dizisinde tutulur ve dışarıyla paylaşılır.
 */
class PartsSheet(
    private val context: Context,
    private val mesh: StepMesh,
    private val partVisible: BooleanArray,
    private val onVisibilityChanged: () -> Unit,
    private val onHighlight: (leafStart: Int, leafEnd: Int) -> Unit,
) {
    private val dialog = BottomSheetDialog(context)
    private val adapter = Adapter()

    // Tek parçalı (montajsız) modellerde kökü göster; montajlarda kök montajı atla.
    private val nodes: List<StepMesh.TreeNode> = mesh.tree

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_parts, null)
        view.findViewById<TextView>(R.id.parts_title).text =
            context.getString(R.string.parts_title, mesh.partCount)
        view.findViewById<MaterialButton>(R.id.parts_show_all).setOnClickListener {
            partVisible.fill(true)
            adapter.notifyDataSetChanged()
            onVisibilityChanged()
        }
        val list = view.findViewById<RecyclerView>(R.id.parts_list)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        dialog.setContentView(view)
        dialog.behavior.peekHeight = (context.resources.displayMetrics.heightPixels * 0.55f).toInt()
        dialog.setOnDismissListener { onHighlight(0, 0) }
        dialog.show()
        // Arka plan karartılmasın: vurgulanan parça modelde görünsün
        dialog.window?.setDimAmount(0f)
    }

    private fun anyVisible(n: StepMesh.TreeNode): Boolean =
        (n.leafStart until n.leafEnd).any { partVisible.getOrElse(it) { false } }

    private fun displayName(n: StepMesh.TreeNode): String = when {
        n.name.isNotBlank() -> n.name
        n.isAssembly -> context.getString(R.string.assembly_fallback)
        else -> context.getString(R.string.part_fallback, n.leafStart + 1)
    }

    private inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val indent: Space = v.findViewById(R.id.part_indent)
        val icon: ImageView = v.findViewById(R.id.part_icon)
        val name: TextView = v.findViewById(R.id.part_name)
        val isolate: MaterialButton = v.findViewById(R.id.part_isolate)
        val visibility: MaterialButton = v.findViewById(R.id.part_visibility)
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = nodes.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_part, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val n = nodes[position]
            val density = context.resources.displayMetrics.density
            h.indent.layoutParams = h.indent.layoutParams.apply {
                width = ((16 + n.depth * 18) * density).toInt()
            }
            h.icon.setImageResource(if (n.isAssembly) R.drawable.ic_assembly else R.drawable.ic_part)
            h.name.text = displayName(n)
            val visible = anyVisible(n)
            h.name.alpha = if (visible) 1f else 0.45f
            h.visibility.setIconResource(if (visible) R.drawable.ic_visibility else R.drawable.ic_visibility_off)

            h.visibility.setOnClickListener {
                val newState = !anyVisible(n)
                for (i in n.leafStart until n.leafEnd) partVisible[i] = newState
                notifyDataSetChanged()
                onVisibilityChanged()
            }
            h.isolate.setOnClickListener {
                for (i in partVisible.indices) partVisible[i] = i >= n.leafStart && i < n.leafEnd
                notifyDataSetChanged()
                onVisibilityChanged()
            }
            h.itemView.setOnClickListener { onHighlight(n.leafStart, n.leafEnd) }
        }
    }
}
