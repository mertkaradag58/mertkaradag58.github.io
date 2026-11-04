package com.mertkaradag.stepviewer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.Locale

/**
 * Model açık değilken görünen ana ekran: dosya aç, klasör ekle, arama, favoriler (küçük
 * resimli kartlar), son açılanlar ve eklenen klasörlerdeki CAD dosyaları.
 */
class HomeScreen(
    private val context: Context,
    private val container: LinearLayout,
    private val recent: RecentFiles,
    private val runInBackground: (() -> Unit) -> Unit,
    private val runOnUi: (() -> Unit) -> Unit,
    private val onOpenFile: () -> Unit,
    private val onAddFolder: () -> Unit,
    private val onOpenUri: (uri: Uri, name: String) -> Unit,
    private val onRemoveFolder: (CadFiles.Folder) -> Unit,
) {
    private val d = context.resources.displayMetrics.density
    private var query = ""
    private var searchField: EditText? = null
    private val listArea = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** Klasör içerikleri (arka planda okunur, önbellek). */
    private val folderFiles = HashMap<Uri, List<CadFiles.FolderFile>>()
    private val expanded = HashSet<Uri>()
    private val loadingFolders = HashSet<Uri>()

    private fun dp(v: Int) = (v * d).toInt()
    private fun color(id: Int) = context.getColor(id)

    fun build() {
        container.removeAllViews()

        // Başlık: simge + karşılama
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFFF9A4D.toInt(), 0xFFE8650E.toInt()),
            ).apply { cornerRadius = 18 * d }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_cube)
                imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(60), dp(60)).apply { marginEnd = dp(16) })
        head.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = context.getString(R.string.empty_title)
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.text))
            })
            addView(TextView(context).apply {
                text = context.getString(R.string.home_subtitle)
                textSize = 13.5f
                setTextColor(color(R.color.text_secondary))
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(head)

        // Ana düğmeler
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20), 0, 0)
        }
        val open = MaterialButton(context).apply {
            text = context.getString(R.string.empty_open)
            setIconResource(R.drawable.ic_folder_open)
            minHeight = dp(52)
            cornerRadius = dp(16)
            setOnClickListener { onOpenFile() }
        }
        buttons.addView(open, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
        val folder = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = context.getString(R.string.home_add_folder)
            setIconResource(R.drawable.ic_folder_add)
            minHeight = dp(52)
            cornerRadius = dp(16)
            setOnClickListener { onAddFolder() }
        }
        buttons.addView(folder, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(buttons)

        // Arama
        val search = EditText(context).apply {
            hint = context.getString(R.string.home_search)
            setSingleLine()
            textSize = 15f
            setTextColor(color(R.color.text))
            setHintTextColor(color(R.color.text_secondary))
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(color(R.color.surface_solid))
                setStroke(dp(1), color(R.color.outline))
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            compoundDrawableTintList = ColorStateList.valueOf(color(R.color.text_secondary))
            setText(query)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty().trim()
                    // Arama yapılırken tüm klasörler taranır
                    if (query.isNotEmpty()) for (f in CadFiles.folders(context)) loadFolder(f.uri)
                    renderLists()
                }
            })
        }
        searchField = search
        container.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        container.addView(listArea)
        renderLists()

        // Desteklenen biçimler ve hareketler
        container.addView(TextView(context).apply {
            text = context.getString(R.string.empty_subtitle)
            textSize = 12.5f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(28), 0, 0)
            gravity = Gravity.CENTER
        })
        container.addView(TextView(context).apply {
            text = context.getString(R.string.gesture_hint)
            textSize = 12f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(10), 0, 0)
            gravity = Gravity.CENTER
        })
    }

    fun refresh() {
        folderFiles.clear()
        renderLists()
    }

    /** Yalnız listeleri yeniden çizer (klasör önbelleği korunur). */
    fun redrawLists() = renderLists()

    private fun matches(name: String) = query.isEmpty() || name.lowercase(Locale.getDefault()).contains(query.lowercase(Locale.getDefault()))

    private fun renderLists() {
        listArea.removeAllViews()
        val favs = recent.favorites().filter { matches(it.name) }
        val favUris = favs.map { it.uri }.toSet()
        if (favs.isNotEmpty()) {
            sectionTitle(R.string.home_favorites)
            val scroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
            }
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (f in favs) row.addView(favoriteCard(f))
            scroll.addView(row)
            listArea.addView(scroll)
        }

        val items = recent.list().filter { matches(it.name) }
        if (items.isNotEmpty()) {
            sectionTitle(R.string.recent_files)
            val card = listCard()
            for (item in items) card.addView(recentRow(item, item.uri in favUris || recent.isFavorite(item.uri)))
            listArea.addView(card.parent as View)
        }

        val folders = CadFiles.folders(context)
        if (folders.isNotEmpty()) {
            sectionTitle(R.string.home_folders)
            for (f in folders) {
                val card = listCard()
                card.addView(folderHeader(f))
                val open = expanded.contains(f.uri) || query.isNotEmpty()
                if (open) {
                    val files = folderFiles[f.uri]
                    if (files == null) {
                        loadFolder(f.uri)
                        card.addView(hintRow(context.getString(R.string.home_folder_loading)))
                    } else {
                        val shown = files.filter { matches(it.name) }
                        if (shown.isEmpty()) {
                            card.addView(hintRow(context.getString(R.string.home_folder_empty)))
                        } else {
                            for (file in shown.take(200)) card.addView(folderFileRow(file))
                        }
                    }
                }
                listArea.addView(card.parent as View)
            }
        }

        if (favs.isEmpty() && items.isEmpty() && folders.isEmpty()) {
            listArea.addView(TextView(context).apply {
                text = context.getString(R.string.home_empty_hint)
                textSize = 13.5f
                setTextColor(color(R.color.text_secondary))
                setPadding(dp(4), dp(24), dp(4), 0)
                gravity = Gravity.CENTER
            })
        } else if (query.isNotEmpty() && listArea.childCount == 0) {
            listArea.addView(hintRow(context.getString(R.string.parts_no_match)))
        }
    }

    private fun loadFolder(uri: Uri) {
        if (folderFiles.containsKey(uri) || !loadingFolders.add(uri)) return
        runInBackground {
            val files = CadFiles.listFolder(context, uri)
            runOnUi {
                loadingFolders.remove(uri)
                folderFiles[uri] = files
                renderLists()
            }
        }
    }

    private fun sectionTitle(res: Int) {
        listArea.addView(TextView(context).apply {
            text = context.getString(res).uppercase(Locale.getDefault())
            textSize = 12f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.accent))
            setPadding(dp(4), dp(24), 0, dp(8))
        })
    }

    /** Beyaz yuvarlak köşeli kart; içine satırlar eklenir (dönüş: içerik). */
    private fun listCard(): LinearLayout {
        val card = MaterialCardView(context).apply {
            radius = 18 * d
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = color(R.color.outline)
            setCardBackgroundColor(color(R.color.surface_solid))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        card.addView(inner)
        return inner
    }

    private fun thumbView(uri: String, sizeDp: Int): View {
        val box = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(color(R.color.surface_variant))
            }
            clipToOutline = true
        }
        val bmp = Thumbs.load(context, uri)
        if (bmp != null) {
            box.addView(ImageView(context).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            box.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_cube)
                imageTintList = ColorStateList.valueOf(color(R.color.text_secondary))
            }, FrameLayout.LayoutParams(dp(sizeDp / 2), dp(sizeDp / 2), Gravity.CENTER))
        }
        box.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return box
    }

    private fun favoriteCard(e: RecentFiles.Entry): View {
        val card = MaterialCardView(context).apply {
            radius = 18 * d
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = color(R.color.outline)
            setCardBackgroundColor(color(R.color.surface_solid))
            layoutParams = LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) }
            setOnClickListener { onOpenUri(e.uri, e.name) }
            setOnLongClickListener {
                recent.toggleFavorite(e.uri, e.name)
                renderLists()
                true
            }
        }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(10))
        }
        inner.addView(thumbView(e.uri.toString(), 116))
        inner.addView(TextView(context).apply {
            text = e.name
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(color(R.color.text))
            setPadding(dp(2), dp(8), dp(2), 0)
        })
        card.addView(inner)
        return card
    }

    private fun recentRow(e: RecentFiles.Entry, fav: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(2), dp(6))
            background = selectable()
            setOnClickListener { onOpenUri(e.uri, e.name) }
        }
        row.addView(thumbView(e.uri.toString(), 48))
        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
            addView(TextView(context).apply {
                text = e.name
                textSize = 15f
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setTextColor(color(R.color.text))
            })
            addView(TextView(context).apply {
                text = DateFormat.getMediumDateFormat(context).format(e.time) + " " +
                    DateFormat.getTimeFormat(context).format(e.time)
                textSize = 12f
                setTextColor(color(R.color.text_secondary))
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(iconButton(if (fav) R.drawable.ic_star else R.drawable.ic_star_outline,
            if (fav) R.color.accent else R.color.text_secondary, R.string.home_favorite) {
            recent.toggleFavorite(e.uri, e.name)
            renderLists()
        })
        row.addView(iconButton(R.drawable.ic_close, R.color.text_secondary, R.string.home_remove) {
            recent.remove(e.uri)
            renderLists()
        })
        return row
    }

    private fun folderHeader(f: CadFiles.Folder): View {
        val open = expanded.contains(f.uri)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(2), dp(8))
            background = selectable()
            setOnClickListener {
                if (!expanded.remove(f.uri)) expanded.add(f.uri)
                renderLists()
            }
        }
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_folder)
            imageTintList = ColorStateList.valueOf(color(R.color.accent))
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        val count = folderFiles[f.uri]?.size
        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(4), 0)
            addView(TextView(context).apply {
                text = f.name
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setSingleLine()
                setTextColor(color(R.color.text))
            })
            addView(TextView(context).apply {
                text = if (count != null) context.getString(R.string.home_folder_count, count)
                else context.getString(if (open) R.string.home_folder_loading else R.string.home_folder_tap)
                textSize = 12f
                setTextColor(color(R.color.text_secondary))
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(iconButton(R.drawable.ic_restore, R.color.text_secondary, R.string.home_folder_refresh) {
            folderFiles.remove(f.uri)
            expanded.add(f.uri)
            renderLists()
        })
        row.addView(iconButton(R.drawable.ic_close, R.color.text_secondary, R.string.home_folder_remove) {
            onRemoveFolder(f)
        })
        return row
    }

    private fun folderFileRow(f: CadFiles.FolderFile): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(52), dp(8), dp(14), dp(8))
            background = selectable()
            setOnClickListener { onOpenUri(f.uri, f.name) }
        }
        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = f.name
                textSize = 14.5f
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setTextColor(color(R.color.text))
            })
            val info = buildList {
                if (f.path.isNotEmpty()) add(f.path)
                if (f.size >= 0) add(Formatter.formatShortFileSize(context, f.size))
                if (f.modified > 0) add(DateFormat.getMediumDateFormat(context).format(f.modified))
            }.joinToString(" · ")
            if (info.isNotEmpty()) addView(TextView(context).apply {
                text = info
                textSize = 12f
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.START
                setTextColor(color(R.color.text_secondary))
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = CadFiles.extension(f.name).uppercase(Locale.ROOT)
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.on_accent_container))
            background = GradientDrawable().apply {
                cornerRadius = 8 * d
                setColor(color(R.color.accent_container))
            }
            setPadding(dp(8), dp(3), dp(8), dp(3))
        })
        return row
    }

    private fun hintRow(text: String): View = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(color(R.color.text_secondary))
        setPadding(dp(16), dp(10), dp(16), dp(12))
    }

    private fun iconButton(icon: Int, tint: Int, desc: Int, onClick: () -> Unit): View =
        MaterialButton(context, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            setIconResource(icon)
            iconTint = ColorStateList.valueOf(color(tint))
            contentDescription = context.getString(desc)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }

    private fun selectable(): android.graphics.drawable.Drawable? {
        val out = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return context.getDrawable(out.resourceId)
    }
}
