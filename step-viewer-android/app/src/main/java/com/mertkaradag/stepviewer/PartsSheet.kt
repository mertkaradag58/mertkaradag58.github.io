package com.mertkaradag.stepviewer

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Space
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

/**
 * Montaj ağacı: parçaları göster/gizle, yalnız birini göster, ada göre ara.
 * Satıra dokunmak o parçayı (veya alt montajı) modelde seçer.
 * Görünürlük durumu [partVisible] dizisinde tutulur ve dışarıyla paylaşılır.
 */
class PartsSheet(
    private val context: Context,
    private val mesh: StepMesh,
    private val partVisible: BooleanArray,
    /** Modelde seçili parça (-1: yok); listede işaretlenir ve ona kaydırılır. */
    private val selectedPart: Int,
    private val onVisibilityChanged: () -> Unit,
    /** Satıra dokunuldu: [leafStart, leafEnd) aralığı. */
    private val onSelect: (leafStart: Int, leafEnd: Int) -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val dialog = BottomSheetDialog(context)
    private val adapter = Adapter()
    private val locale = Locale("tr", "TR")

    private val allNodes: List<StepMesh.TreeNode> = mesh.tree
    private var nodes: List<StepMesh.TreeNode> = allNodes
    private var filtering = false
    private var selStart = if (selectedPart >= 0) selectedPart else -1
    private var selEnd = if (selectedPart >= 0) selectedPart + 1 else -1

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_parts, null)
        view.findViewById<TextView>(R.id.parts_title).text =
            context.getString(R.string.parts_title, mesh.partCount)
        view.findViewById<MaterialButton>(R.id.parts_show_all).setOnClickListener {
            partVisible.fill(true)
            adapter.notifyDataSetChanged()
            onVisibilityChanged()
        }
        val empty = view.findViewById<TextView>(R.id.parts_empty)
        val list = view.findViewById<RecyclerView>(R.id.parts_list)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter

        view.findViewById<TextInputEditText>(R.id.parts_search).doAfterTextChanged { text ->
            val q = text?.toString()?.trim()?.lowercase(locale).orEmpty()
            filtering = q.isNotEmpty()
            nodes = if (!filtering) allNodes else allNodes.filter { displayName(it).lowercase(locale).contains(q) }
            empty.visibility = if (nodes.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
        }

        dialog.setContentView(view)
        dialog.behavior.peekHeight = (context.resources.displayMetrics.heightPixels * 0.55f).toInt()
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        // Arka plan karartılmasın: seçilen parça modelde görünsün
        dialog.window?.setDimAmount(0f)

        // Modelde seçili parçanın satırına kaydır
        if (selectedPart >= 0) {
            val pos = nodes.indexOfFirst { !it.isAssembly && it.leafStart == selectedPart }
            if (pos >= 0) list.post { (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 120) }
        }
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
            val depth = if (filtering) 0 else n.depth
            h.indent.layoutParams = h.indent.layoutParams.apply {
                width = ((16 + depth * 18) * density).toInt()
            }
            h.icon.setImageResource(if (n.isAssembly) R.drawable.ic_assembly else R.drawable.ic_part)
            h.name.text = displayName(n)
            val visible = anyVisible(n)
            h.name.alpha = if (visible) 1f else 0.45f
            h.visibility.setIconResource(if (visible) R.drawable.ic_visibility else R.drawable.ic_visibility_off)

            val selected = n.leafStart == selStart && n.leafEnd == selEnd
            if (selected) {
                h.itemView.setBackgroundResource(R.drawable.part_selected_background)
            } else {
                val tv = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                h.itemView.setBackgroundResource(tv.resourceId)
            }

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
            h.itemView.setOnClickListener {
                selStart = n.leafStart
                selEnd = n.leafEnd
                notifyDataSetChanged()
                onSelect(n.leafStart, n.leafEnd)
            }
        }
    }
}
