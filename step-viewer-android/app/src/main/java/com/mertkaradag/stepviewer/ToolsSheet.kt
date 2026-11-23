package com.mertkaradag.stepviewer

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.tabs.TabLayout
import java.util.Locale
import kotlin.math.abs

/**
 * Model incelerken açılan araç paneli: eski üç nokta menüsünün yerini alır.
 *
 * Araçlar kategorilere (sekmelere) ayrılmış, simgeli kutucuklar halinde gösterilir. Sekmeler
 * arasında dokunarak veya sağa-sola kaydırarak geçilir; üstte araç arama ve son kullanılan
 * araçlar vardır. Kutucuklar sekme değişince sırayla belirir (kademeli animasyon).
 */
class ToolsSheet(
    private val context: Context,
    private val title: String,
    private val subtitle: String?,
    private val categories: List<Category>,
    private val tools: List<Tool>,
) {
    class Category(val key: String, val label: String, val icon: Int, val color: Int)

    class Tool(
        val id: String,
        val category: String,
        val icon: Int,
        val title: String,
        val caption: String?,
        val enabled: Boolean = true,
        val badge: String? = null,
        /** Arama için ek anahtar kelimeler. */
        val keywords: String = "",
        val action: () -> Unit,
    )

    private val d = context.resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()
    private fun dp(v: Int) = (v * d).toInt()
    private fun color(id: Int) = context.getColor(id)
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private val dialog = BottomSheetDialog(context)
    private val pages = ArrayList<View>()
    private lateinit var tabs: TabLayout
    private lateinit var pageFrame: FrameLayout
    private lateinit var searchResults: LinearLayout
    private lateinit var recentRow: View
    private var current = 0

    private fun catColor(key: String) = categories.firstOrNull { it.key == key }?.color ?: color(R.color.accent)

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(20))
        }
        root.addView(BottomSheetDragHandleView(context))

        // Başlık
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(10))
        }
        head.addView(TextView(context).apply {
            text = context.getString(R.string.tools_title)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text))
        })
        if (!subtitle.isNullOrEmpty()) {
            head.addView(TextView(context).apply {
                text = subtitle
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setTextColor(color(R.color.text_secondary))
            })
        }
        root.addView(head)

        // Arama
        val search = EditText(context).apply {
            hint = context.getString(R.string.tools_search)
            setSingleLine()
            textSize = 15f
            setTextColor(color(R.color.text))
            setHintTextColor(color(R.color.text_secondary))
            background = GradientDrawable().apply {
                cornerRadius = 100 * d
                setColor(color(R.color.surface_variant))
            }
            setPadding(dp(18), dp(11), dp(18), dp(11))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            compoundDrawableTintList = ColorStateList.valueOf(color(R.color.text_secondary))
        }
        root.addView(search, lp(match = true).apply {
            leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(12)
        })

        // Son kullanılanlar
        recentRow = buildRecentRow()
        root.addView(recentRow)

        // Sekmeler
        tabs = TabLayout(context).apply {
            tabMode = TabLayout.MODE_SCROLLABLE
            tabGravity = TabLayout.GRAVITY_START
            isInlineLabel = true
            tabRippleColor = null
            setSelectedTabIndicator(GradientDrawable().apply { cornerRadius = 100 * d; setColor(Color.WHITE) })
            setSelectedTabIndicatorColor(color(R.color.accent_container))
            setSelectedTabIndicatorGravity(TabLayout.INDICATOR_GRAVITY_STRETCH)
            tabIndicatorAnimationMode = TabLayout.INDICATOR_ANIMATION_MODE_ELASTIC
            isTabIndicatorFullWidth = true
            setTabTextColors(color(R.color.text_secondary), color(R.color.on_accent_container))
            tabIconTint = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                intArrayOf(color(R.color.on_accent_container), color(R.color.text_secondary)),
            )
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(12), 0, dp(12), 0)
            clipToPadding = false
        }
        for (c in categories) tabs.addTab(tabs.newTab().setText(c.label).setIcon(c.icon))
        root.addView(tabs, lp(match = true).apply { bottomMargin = dp(8) })

        // Sayfalar: hepsi üst üste; yükseklik en kalabalık sayfaya göre sabit kalır
        pageFrame = SwipeFrame(context) { dir -> select(current + dir) }
        for (c in categories) {
            val grid = buildGrid(tools.filter { it.category == c.key })
            grid.visibility = View.INVISIBLE
            pages += grid
            pageFrame.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(pageFrame, lp(match = true))

        searchResults = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(searchResults, lp(match = true))

        val start = prefs.getInt("tools_tab", 0).coerceIn(0, categories.size - 1)
        current = start
        pages[start].visibility = View.VISIBLE
        tabs.getTabAt(start)?.select()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPage(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = runSearch(s?.toString().orEmpty().trim())
        })

        val scroll = NestedScrollView(context).apply {
            isFillViewport = true
            addView(root)
        }
        dialog.setContentView(scroll)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
        root.post { animateIn(pages[start], 0) }
    }

    private fun lp(match: Boolean) = LinearLayout.LayoutParams(
        if (match) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun select(index: Int) {
        if (index < 0 || index >= categories.size) return
        tabs.getTabAt(index)?.select()
    }

    private fun showPage(index: Int) {
        if (index == current) return
        val dir = if (index > current) 1 else -1
        val old = pages[current]
        val new = pages[index]
        current = index
        prefs.edit().putInt("tools_tab", index).apply()
        old.animate().cancel()
        old.animate()
            .alpha(0f)
            .translationX(-dir * 48 * d)
            .setDuration(140)
            .withEndAction {
                old.visibility = View.INVISIBLE
                old.translationX = 0f
                old.alpha = 1f
            }
            .start()
        new.visibility = View.VISIBLE
        new.alpha = 1f
        new.translationX = dir * 48 * d
        new.animate().translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator(2f)).start()
        animateIn(new, 40)
        tabs.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Kutucuklar sırayla, hafif büyüyerek belirir. */
    private fun animateIn(page: View, baseDelay: Long) {
        val tiles = ArrayList<View>()
        fun collect(v: View) {
            if (v.getTag(R.id.tag_tile) == true) tiles += v
            else if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
        }
        collect(page)
        tiles.forEachIndexed { i, t ->
            t.animate().cancel()
            t.alpha = 0f
            t.translationY = 14 * d
            t.scaleX = 0.92f
            t.scaleY = 0.92f
            t.animate()
                .alpha(if (t.isEnabled) 1f else 0.4f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(baseDelay + i * 28L)
                .setDuration(260)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }
    }

    // -----------------------------------------------------------------------

    private fun buildGrid(list: List<Tool>): LinearLayout {
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), 0)
        }
        val cols = 3
        var row: LinearLayout? = null
        list.forEachIndexed { i, t ->
            if (i % cols == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, lp(match = true))
            }
            row!!.addView(buildTile(t), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        // Son satırı boşlukla doldur (kutucuklar eşit genişlikte kalsın)
        val rest = (cols - list.size % cols) % cols
        repeat(rest) { row?.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }) }
        return grid
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildTile(t: Tool): View {
        val c = catColor(t.category)
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(14), dp(6), dp(12))
            val shape = GradientDrawable().apply {
                cornerRadius = 22 * d
                setColor(color(R.color.surface_variant))
            }
            val mask = GradientDrawable().apply { cornerRadius = 22 * d; setColor(Color.WHITE) }
            background = RippleDrawable(ColorStateList.valueOf(withAlpha(c, 0x40)), shape, mask)
            isEnabled = t.enabled
            alpha = if (t.enabled) 1f else 0.4f
            setTag(R.id.tag_tile, true)
            contentDescription = t.title
        }
        val iconBox = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(withAlpha(c, 0x38), withAlpha(c, 0x1A)),
            ).apply { cornerRadius = 16 * d }
        }
        iconBox.addView(ImageView(context).apply {
            setImageResource(t.icon)
            imageTintList = ColorStateList.valueOf(c)
        }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        val iconWrap = FrameLayout(context)
        iconWrap.addView(iconBox, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER))
        if (t.badge != null) {
            iconWrap.addView(TextView(context).apply {
                text = t.badge
                textSize = 8.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                letterSpacing = 0.06f
                setPadding(dp(5), dp(1), dp(5), dp(1))
                background = GradientDrawable().apply { cornerRadius = 100 * d; setColor(c) }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        }
        tile.addView(iconWrap, LinearLayout.LayoutParams(dp(66), dp(54)))
        tile.addView(TextView(context).apply {
            text = t.title
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 2
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text))
        }, lp(match = true).apply { topMargin = dp(8) })
        if (!t.caption.isNullOrEmpty()) {
            tile.addView(TextView(context).apply {
                text = t.caption
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(color(R.color.text_secondary))
            }, lp(match = true).apply { topMargin = dp(2) })
        }
        if (t.enabled) {
            tile.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setStartDelay(0).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(160).setInterpolator(OvershootInterpolator(2f)).start()
                }
                false
            }
            tile.setOnClickListener { run(t) }
        }
        return tile
    }

    private fun run(t: Tool) {
        rememberRecent(t.id)
        dialog.dismiss()
        // Panel kapanırken araç açılsın (iki animasyon çakışmasın)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ t.action() }, 120)
    }

    // -----------------------------------------------------------------------
    // Son kullanılanlar
    // -----------------------------------------------------------------------

    private fun recentIds(): List<String> =
        prefs.getString("recent_tools", "").orEmpty().split(',').filter { it.isNotEmpty() }

    private fun rememberRecent(id: String) {
        val list = recentIds().toMutableList()
        list.remove(id)
        list.add(0, id)
        prefs.edit().putString("recent_tools", list.take(6).joinToString(",")).apply()
    }

    private fun buildRecentRow(): View {
        val recent = recentIds().mapNotNull { id -> tools.firstOrNull { it.id == id && it.enabled } }.take(5)
        val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (recent.isEmpty()) {
            wrap.visibility = View.GONE
            return wrap
        }
        wrap.addView(TextView(context).apply {
            text = context.getString(R.string.tools_recent)
            textSize = 12f
            letterSpacing = 0.04f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text_secondary))
            setPadding(dp(20), 0, dp(20), dp(6))
        })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        for (t in recent) {
            val c = catColor(t.category)
            val chip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(14), dp(8))
                val shape = GradientDrawable().apply {
                    cornerRadius = 100 * d
                    setColor(withAlpha(c, 0x1F))
                }
                val mask = GradientDrawable().apply { cornerRadius = 100 * d; setColor(Color.WHITE) }
                background = RippleDrawable(ColorStateList.valueOf(withAlpha(c, 0x40)), shape, mask)
                setOnClickListener { run(t) }
            }
            chip.addView(ImageView(context).apply {
                setImageResource(t.icon)
                imageTintList = ColorStateList.valueOf(c)
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) })
            chip.addView(TextView(context).apply {
                text = t.title
                textSize = 13f
                maxLines = 1
                setTextColor(color(R.color.text))
            })
            row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            })
        }
        wrap.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(row)
        })
        (wrap.getChildAt(1).layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp(12)
        return wrap
    }

    // -----------------------------------------------------------------------
    // Arama
    // -----------------------------------------------------------------------

    private fun normalize(s: String) = s.lowercase(Locale.getDefault())
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g').replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')

    private fun runSearch(q: String) {
        if (q.isEmpty()) {
            searchResults.visibility = View.GONE
            searchResults.removeAllViews()
            tabs.visibility = View.VISIBLE
            pageFrame.visibility = View.VISIBLE
            recentRow.visibility = if ((recentRow as ViewGroup).childCount > 0) View.VISIBLE else View.GONE
            return
        }
        val nq = normalize(q)
        val hits = tools.filter { t ->
            normalize(t.title + " " + (t.caption ?: "") + " " + t.keywords).contains(nq)
        }
        tabs.visibility = View.GONE
        pageFrame.visibility = View.GONE
        recentRow.visibility = View.GONE
        searchResults.visibility = View.VISIBLE
        searchResults.removeAllViews()
        if (hits.isEmpty()) {
            searchResults.addView(TextView(context).apply {
                text = context.getString(R.string.tools_no_match)
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(color(R.color.text_secondary))
                setPadding(dp(20), dp(32), dp(20), dp(32))
            })
            return
        }
        val grid = buildGrid(hits)
        searchResults.addView(grid)
        animateIn(grid, 0)
    }

    /** Yatay kaydırmayı yakalayıp sekme değiştirir; dokunmalar kutucuklara geçer. */
    private class SwipeFrame(context: Context, val onSwipe: (dir: Int) -> Unit) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop * 2
        private var downX = 0f
        private var downY = 0f
        private var swiping = false

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    swiping = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) {
                        swiping = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - downX
                    if (swiping || abs(dx) > slop) {
                        if (abs(dx) > slop) onSwipe(if (dx < 0) 1 else -1)
                    }
                    swiping = false
                }
                MotionEvent.ACTION_CANCEL -> swiping = false
            }
            return true
        }
    }

    companion object {
        fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)
    }
}
