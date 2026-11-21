package com.mertkaradag.stepviewer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date

/**
 * Modele iğne gibi tutturulan kalıcı notlar (metin + isteğe bağlı fotoğraf).
 * Notlar dosya adına göre filesDir/notes altında JSON olarak saklanır; fotoğraflar aynı klasördedir.
 * Konumlar model koordinatında (mm) tutulur.
 */
class NotesController(
    private val activity: AppCompatActivity,
    private val onChanged: () -> Unit,
) {

    class Note(
        val id: String,
        var text: String,
        val x: Float,
        val y: Float,
        val z: Float,
        var photo: String?,
        val time: Long,
    )

    private val dir: File get() = File(activity.filesDir, "notes").apply { mkdirs() }
    private var fileKey: String? = null
    private var fileName: String = ""
    val notes = mutableListOf<Note>()

    // Açık düzenleyici durumu (kamera / galeri sonucu buraya yazılır)
    private var editorPhoto: String? = null
    private var editorImage: ImageView? = null
    private var editorRemove: View? = null
    private var pendingCameraFile: File? = null

    private val takePicture = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingCameraFile
        pendingCameraFile = null
        if (ok && f != null && f.length() > 0) setEditorPhoto(f.name) else f?.delete()
    }

    private val cameraPermission =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera()
            else Toast.makeText(activity, R.string.camera_denied, Toast.LENGTH_LONG).show()
        }

    private val pickImage = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) copyPicked(uri)
    }

    // -----------------------------------------------------------------------
    // Depolama
    // -----------------------------------------------------------------------

    fun load(name: String) {
        fileName = name
        fileKey = if (name.isBlank()) null else sha1(name)
        notes.clear()
        val key = fileKey ?: return
        val f = File(dir, "$key.json")
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                notes += Note(
                    id = o.optString("id"),
                    text = o.optString("text"),
                    x = o.optDouble("x").toFloat(),
                    y = o.optDouble("y").toFloat(),
                    z = o.optDouble("z").toFloat(),
                    photo = o.optString("photo").ifBlank { null },
                    time = o.optLong("time"),
                )
            }
        } catch (_: Exception) {
            notes.clear()
        }
    }

    fun clear() {
        fileKey = null
        fileName = ""
        notes.clear()
    }

    private fun save() {
        val key = fileKey ?: return
        val arr = JSONArray()
        for (n in notes) {
            arr.put(
                JSONObject()
                    .put("id", n.id)
                    .put("text", n.text)
                    .put("x", n.x.toDouble())
                    .put("y", n.y.toDouble())
                    .put("z", n.z.toDouble())
                    .put("photo", n.photo ?: "")
                    .put("time", n.time)
            )
        }
        try {
            File(dir, "$key.json").writeText(arr.toString())
        } catch (_: Exception) {
        }
        onChanged()
    }

    /** Çizim için not noktaları (nokta başına x, y, z). */
    fun points(): FloatArray {
        val out = FloatArray(notes.size * 3)
        notes.forEachIndexed { i, n ->
            out[3 * i] = n.x
            out[3 * i + 1] = n.y
            out[3 * i + 2] = n.z
        }
        return out
    }

    /** Ekranda (x, y) noktasına [radiusPx] içinde en yakın not. Kamera arkasındakiler atlanır. */
    fun findNear(mvp: FloatArray, w: Int, h: Int, x: Float, y: Float, radiusPx: Float): Note? {
        var best: Note? = null
        var bestD = radiusPx * radiusPx
        for (n in notes) {
            val cw = mvp[3] * n.x + mvp[7] * n.y + mvp[11] * n.z + mvp[15]
            if (cw <= 1e-6f) continue
            val cx = (mvp[0] * n.x + mvp[4] * n.y + mvp[8] * n.z + mvp[12]) / cw
            val cy = (mvp[1] * n.x + mvp[5] * n.y + mvp[9] * n.z + mvp[13]) / cw
            val sx = (cx * 0.5f + 0.5f) * w
            val sy = (1f - (cy * 0.5f + 0.5f)) * h
            val d = (sx - x) * (sx - x) + (sy - y) * (sy - y)
            if (d < bestD) {
                bestD = d
                best = n
            }
        }
        return best
    }

    // -----------------------------------------------------------------------
    // Arayüz
    // -----------------------------------------------------------------------

    fun addAt(point: FloatArray) {
        if (fileKey == null) return
        val n = Note(
            id = System.currentTimeMillis().toString(36),
            text = "",
            x = point[0],
            y = point[1],
            z = point[2],
            photo = null,
            time = System.currentTimeMillis(),
        )
        openEditor(n, isNew = true)
    }

    fun open(note: Note) = openEditor(note, isNew = false)

    private fun openEditor(note: Note, isNew: Boolean) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        editorPhoto = note.photo

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val input = EditText(activity).apply {
            setText(note.text)
            hint = activity.getString(R.string.note_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
        }
        box.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val image = ImageView(activity).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            setOnClickListener { editorPhoto?.let { viewPhoto(it) } }
        }
        box.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)).apply {
            topMargin = dp(8)
        })

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        fun rowButton(text: Int, icon: Int, click: () -> Unit) =
            MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(text)
                setIconResource(icon)
                iconSize = dp(18)
                setOnClickListener { click() }
            }
        val camera = rowButton(R.string.note_camera, R.drawable.ic_camera_alt) { startCamera() }
        val gallery = rowButton(R.string.note_gallery, R.drawable.ic_image) {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        val remove = rowButton(R.string.note_remove_photo, R.drawable.ic_delete) { setEditorPhoto(null) }
        row.addView(camera, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(gallery, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)
        box.addView(remove, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        editorImage = image
        editorRemove = remove
        setEditorPhoto(editorPhoto, deleteOld = false)

        val index = notes.indexOf(note)
        val title = if (isNew) activity.getString(R.string.note_title_new)
        else activity.getString(R.string.note_title, index + 1)
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.note_save) { _, _ ->
                note.text = input.text.toString().trim()
                val old = note.photo
                note.photo = editorPhoto
                if (old != null && old != note.photo) File(dir, old).delete()
                if (isNew) notes += note
                save()
                Toast.makeText(activity, R.string.note_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                // Kaydedilmeyen yeni fotoğrafı temizle
                val p = editorPhoto
                if (p != null && p != note.photo) File(dir, p).delete()
            }
        if (!isNew) {
            builder.setNeutralButton(R.string.note_delete) { _, _ -> delete(note) }
        }
        val dialog: AlertDialog = builder.create()
        dialog.setOnDismissListener {
            editorImage = null
            editorRemove = null
        }
        dialog.show()
    }

    private fun setEditorPhoto(name: String?, deleteOld: Boolean = true) {
        val previous = editorPhoto
        editorPhoto = name
        // Düzenleyicide geçici olarak değiştirilen ve hiçbir notta kullanılmayan fotoğrafı sil
        if (deleteOld && previous != null && previous != name && notes.none { it.photo == previous }) {
            File(dir, previous).delete()
        }
        val image = editorImage ?: return
        if (name == null) {
            image.setImageDrawable(null)
            image.visibility = View.GONE
            editorRemove?.visibility = View.GONE
            return
        }
        val bmp = decodeThumb(File(dir, name), 1024)
        image.setImageBitmap(bmp)
        image.visibility = if (bmp != null) View.VISIBLE else View.GONE
        editorRemove?.visibility = if (bmp != null) View.VISIBLE else View.GONE
    }

    private fun decodeThumb(f: File, maxSide: Int) = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var sample = 1
        while (o.outWidth / sample > maxSide || o.outHeight / sample > maxSide) sample *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Throwable) {
        null
    }

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        try {
            val f = File(dir, "foto_${System.currentTimeMillis()}.jpg")
            pendingCameraFile = f
            takePicture.launch(uriFor(f))
        } catch (_: Exception) {
            pendingCameraFile = null
            Toast.makeText(activity, R.string.camera_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyPicked(uri: Uri) {
        try {
            val f = File(dir, "foto_${System.currentTimeMillis()}.jpg")
            activity.contentResolver.openInputStream(uri)?.use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
            if (f.length() > 0) setEditorPhoto(f.name) else f.delete()
        } catch (_: Exception) {
        }
    }

    private fun viewPhoto(name: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriFor(File(dir, name)), "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun delete(note: Note) {
        note.photo?.let { File(dir, it).delete() }
        notes.remove(note)
        save()
    }

    /** Not listesi: dokununca not açılır; paylaş düğmesi metin + fotoğrafları gönderir. */
    fun showList() {
        if (notes.isEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setMessage(R.string.note_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val photoTag = activity.getString(R.string.note_photo)
        val items = notes.mapIndexed { i, n ->
            val text = n.text.ifBlank { "…" }.lineSequence().first().take(60)
            "${i + 1}. $text" + if (n.photo != null) "  $photoTag" else ""
        }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.menu_notes, notes.size))
            .setItems(items) { _, which -> notes.getOrNull(which)?.let { open(it) } }
            .setPositiveButton(R.string.note_share) { _, _ -> share() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun share() {
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val sb = StringBuilder(activity.getString(R.string.note_report_title, fileName)).append("\n\n")
        val uris = ArrayList<Uri>()
        notes.forEachIndexed { i, n ->
            sb.append(i + 1).append(". ").append(n.text.ifBlank { "—" }).append('\n')
            sb.append("   X ").append(Units.fl(n.x)).append(" · Y ").append(Units.fl(n.y))
                .append(" · Z ").append(Units.fl(n.z)).append(' ').append(Units.label)
            sb.append(" · ").append(df.format(Date(n.time))).append('\n')
            n.photo?.let { p ->
                val f = File(dir, p)
                if (f.exists()) {
                    try {
                        uris += uriFor(f)
                        sb.append("   ").append(activity.getString(R.string.note_photo)).append(" #").append(uris.size).append('\n')
                    } catch (_: Exception) {
                    }
                }
            }
        }
        val send = if (uris.isEmpty()) {
            Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        send.putExtra(Intent.EXTRA_TEXT, sb.toString())
        send.putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.note_report_title, fileName))
        try {
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.note_share)))
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun uriFor(f: File): Uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", f)

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
