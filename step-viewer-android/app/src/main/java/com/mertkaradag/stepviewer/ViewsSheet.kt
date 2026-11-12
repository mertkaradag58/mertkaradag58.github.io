package com.mertkaradag.stepviewer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Görünüm paneli: hazır kamera yönleri, görüntü modu, kayıtlı görünümler ve kamera ayarları
 * tek yerde. Eski açılır görünüm menüsünün ve alt çubuktaki görüntü modu düğmesinin yerini alır.
 */
class ViewsSheet(
    private val context: Context,
    private val host: Host,
) {
    interface Host {
        fun goToView(view: Camera.View)
        fun fitView()
        var displayMode: ModelRenderer.DisplayMode
        var orthographic: Boolean
        var yUp: Boolean
        var freeOrbit: Boolean
        var rotateSpeedLevel: Int
        fun savedViewNames(): List<String>
        fun goToSaved(index: Int)
        fun saveView()
        fun manageViews()
    }

    private val d = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun color(id: Int) = context.getColor(id)
    private val dialog = BottomSheetDialog(context)

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        root.addView(BottomSheetDragHandleView(context))
        root.addView(TextView(context).apply {
            text = context.getString(R.string.views_title)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text))
            setPadding(dp(4), 0, dp(4), dp(4))
        })

        // Kamera yönleri
        root.addView(header(R.string.views_camera))
        val views = listOf(
            R.string.view_iso to Camera.View.ISO,
            R.string.view_front to Camera.View.FRONT,
            R.string.view_top to Camera.View.TOP,
            R.string.view_right to Camera.View.RIGHT,
            R.string.view_back to Camera.View.BACK,
            R.string.view_bottom to Camera.View.BOTTOM,
            R.string.view_left to Camera.View.LEFT,
        )
        val cells = ArrayList<View>()
        views.forEach { (label, v) ->
            cells += viewTile(context.getString(label), R.drawable.ic_cube) {
                host.goToView(v)
                dialog.dismiss()
            }
        }
        cells += viewTile(context.getString(R.string.fit), R.drawable.ic_fit) {
            host.fitView()
            dialog.dismiss()
        }
        for (r in 0 until 2) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 4) {
                row.addView(cells[r * 4 + c], LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            root.addView(row)
        }

        // Görüntü modu
        root.addView(header(R.string.views_display))
        val modes = listOf(
            ModelRenderer.DisplayMode.SHADED_EDGES to R.string.display_shaded_edges,
            ModelRenderer.DisplayMode.SHADED to R.string.display_shaded,
            ModelRenderer.DisplayMode.XRAY to R.string.display_xray,
            ModelRenderer.DisplayMode.WIREFRAME to R.string.display_wireframe,
            ModelRenderer.DisplayMode.HIDDEN_LINE to R.string.display_hidden_line,
        )
        val modeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val pills = ArrayList<TextView>()
        fun refreshPills() {
            modes.forEachIndexed { i, (m, _) -> stylePill(pills[i], m == host.displayMode) }
        }
        modes.forEach { (m, label) ->
            val p = TextView(context).apply {
                text = context.getString(label)
                textSize = 13.5f
                setPadding(dp(16), dp(9), dp(16), dp(9))
                setOnClickListener {
                    host.displayMode = m
                    refreshPills()
                    animate().scaleX(1.08f).scaleY(1.08f).setDuration(90).withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(OvershootInterpolator(2f)).start()
                    }.start()
                }
            }
            pills += p
            modeRow.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            })
        }
        refreshPills()
        root.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(modeRow)
        })

        // Kayıtlı görünümler
        root.addView(header(R.string.views_saved))
        val savedRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        host.savedViewNames().forEachIndexed { i, name ->
            savedRow.addView(chip("★ $name", false) {
                host.goToSaved(i)
                dialog.dismiss()
            }, chipLp())
        }
        savedRow.addView(chip("+ " + context.getString(R.string.views_save_short), true) {
            dialog.dismiss()
            host.saveView()
        }, chipLp())
        if (host.savedViewNames().isNotEmpty()) {
            savedRow.addView(chip(context.getString(R.string.views_edit), false) {
                dialog.dismiss()
                host.manageViews()
            }, chipLp())
        }
        root.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(savedRow)
        })

        // Kamera ayarları
        root.addView(header(R.string.views_options))
        root.addView(switchRow(R.string.view_ortho, host.orthographic) { host.orthographic = it })
        root.addView(switchRow(R.string.view_y_up, host.yUp) { host.yUp = it })
        root.addView(switchRow(R.string.view_free_orbit, host.freeOrbit) { host.freeOrbit = it })

        root.addView(TextView(context).apply {
            text = context.getString(R.string.rotate_speed)
            textSize = 15f
            setTextColor(color(R.color.text))
            setPadding(dp(4), dp(10), dp(4), dp(6))
        })
        val group = MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val speedLabels = intArrayOf(R.string.rotate_speed_slow, R.string.rotate_speed_normal, R.string.rotate_speed_fast)
        val ids = IntArray(3) { View.generateViewId() }
        speedLabels.forEachIndexed { i, s ->
            group.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = ids[i]
                text = context.getString(s)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        group.check(ids[host.rotateSpeedLevel.coerceIn(0, 2)])
        group.addOnButtonCheckedListener { _, id, checked ->
            if (checked) host.rotateSpeedLevel = ids.indexOf(id)
        }
        root.addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialog.setContentView(NestedScrollView(context).apply { addView(root) })
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    private fun header(res: Int) = TextView(context).apply {
        text = context.getString(res).uppercase()
        textSize = 12f
        letterSpacing = 0.08f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.accent))
        setPadding(dp(4), dp(18), dp(4), dp(8))
    }

    private fun viewTile(label: String, icon: Int, onClick: () -> Unit): View {
        val accent = color(R.color.accent)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(12), dp(4), dp(10))
            val shape = GradientDrawable().apply { cornerRadius = 18 * d; setColor(color(R.color.surface_variant)) }
            val mask = GradientDrawable().apply { cornerRadius = 18 * d; setColor(Color.WHITE) }
            background = RippleDrawable(ColorStateList.valueOf(ToolsSheet.withAlpha(accent, 0x40)), shape, mask)
            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(accent)
            }, LinearLayout.LayoutParams(dp(26), dp(26)))
            addView(TextView(context).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(color(R.color.text))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            setOnClickListener { onClick() }
        }
    }

    private fun stylePill(p: TextView, selected: Boolean) {
        p.background = GradientDrawable().apply {
            cornerRadius = 100 * d
            if (selected) setColor(color(R.color.accent)) else {
                setColor(color(R.color.surface_variant))
            }
        }
        p.setTextColor(if (selected) color(R.color.on_accent) else color(R.color.text))
        p.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun chip(text: String, primary: Boolean, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 13.5f
        setPadding(dp(14), dp(9), dp(14), dp(9))
        setTextColor(if (primary) color(R.color.on_accent_container) else color(R.color.text))
        val shape = GradientDrawable().apply {
            cornerRadius = 100 * d
            setColor(if (primary) color(R.color.accent_container) else color(R.color.surface_variant))
        }
        val mask = GradientDrawable().apply { cornerRadius = 100 * d; setColor(Color.WHITE) }
        background = RippleDrawable(ColorStateList.valueOf(ToolsSheet.withAlpha(color(R.color.accent), 0x40)), shape, mask)
        setOnClickListener { onClick() }
    }

    private fun chipLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginEnd = dp(8)
    }

    private fun switchRow(label: Int, value: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), 0, dp(2))
        }
        val sw = MaterialSwitch(context).apply {
            isChecked = value
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        row.addView(TextView(context).apply {
            text = context.getString(label)
            textSize = 15f
            setTextColor(color(R.color.text))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }
}
