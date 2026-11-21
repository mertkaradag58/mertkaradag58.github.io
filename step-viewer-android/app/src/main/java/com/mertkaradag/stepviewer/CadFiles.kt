package com.mertkaradag.stepviewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile

/** Desteklenen uzantılar, klasör tarama ve ZIP içinden açma. */
object CadFiles {

    val MODEL_EXTENSIONS = setOf(
        "step", "stp", "p21", "igs", "iges", "brep", "brp",
        "obj", "stl", "gltf", "glb", "dae", "3ds", "fbx", "ply", "3mf", "amf", "off",
    )

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun isModel(name: String) = extension(name) in MODEL_EXTENSIONS

    fun isZip(name: String) = extension(name) == "zip"

    fun isSupported(name: String) = isModel(name) || isZip(name)

    // -----------------------------------------------------------------------
    // ZIP
    // -----------------------------------------------------------------------

    class ZipEntryInfo(val name: String, val size: Long)

    /** Arşivdeki model dosyaları (klasörler ve macOS ek dosyaları atlanır). */
    @Throws(IOException::class)
    fun zipModels(zip: File): List<ZipEntryInfo> = ZipFile(zip).use { z ->
        z.entries().asSequence()
            .filter { !it.isDirectory && !it.name.startsWith("__MACOSX/") && !it.name.substringAfterLast('/').startsWith("._") }
            .filter { isModel(it.name) }
            .map { ZipEntryInfo(it.name, it.size) }
            .toList()
    }

    /**
     * Arşivden tek bir dosyayı önbelleğe çıkarır. Dosya adı yoldan arındırılır (../ ile dışarı
     * yazma olmaz); OBJ'nin yanındaki MTL gibi aynı klasördeki küçük yardımcı dosyalar da çıkarılır.
     */
    @Throws(IOException::class)
    fun extract(zip: File, entryName: String, outDir: File): File {
        outDir.deleteRecursively()
        outDir.mkdirs()
        ZipFile(zip).use { z ->
            val entry = z.getEntry(entryName) ?: throw IOException("zip_entry")
            val folder = entryName.substringBeforeLast('/', "")
            val target = File(outDir, safeName(entryName))
            copyLimited(z, entry, target)
            // Aynı klasördeki malzeme / doku dosyaları (OBJ + MTL, glTF + BIN)
            for (e in z.entries()) {
                if (e.isDirectory || e.name == entryName) continue
                if (e.name.substringBeforeLast('/', "") != folder) continue
                val ext = extension(e.name)
                if (ext in setOf("mtl", "bin", "png", "jpg", "jpeg") && e.size in 0..(50L shl 20)) {
                    copyLimited(z, e, File(outDir, safeName(e.name)))
                }
            }
            return target
        }
    }

    private fun safeName(entry: String): String =
        entry.substringAfterLast('/').substringAfterLast('\\').ifBlank { "model.dat" }

    private fun copyLimited(z: ZipFile, e: java.util.zip.ZipEntry, target: File) {
        z.getInputStream(e).use { src ->
            target.outputStream().use { dst ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_EXTRACT) throw IOException("zip_too_large")
                    dst.write(buf, 0, n)
                }
            }
        }
    }

    private const val MAX_EXTRACT = 2_000_000_000L

    // -----------------------------------------------------------------------
    // Klasörler (Storage Access Framework)
    // -----------------------------------------------------------------------

    class Folder(val uri: Uri, val name: String)

    class FolderFile(val uri: Uri, val name: String, val size: Long, val modified: Long, val path: String)

    fun folders(context: Context): List<Folder> {
        val json = context.getSharedPreferences("folders", Context.MODE_PRIVATE).getString("items", null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Folder(Uri.parse(o.getString("uri")), o.optString("name"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addFolder(context: Context, tree: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val name = folderName(context, tree)
        val items = folders(context).filter { it.uri != tree } + Folder(tree, name)
        saveFolders(context, items)
    }

    fun removeFolder(context: Context, tree: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        saveFolders(context, folders(context).filter { it.uri != tree })
    }

    private fun saveFolders(context: Context, items: List<Folder>) {
        val arr = JSONArray()
        for (f in items) arr.put(org.json.JSONObject().put("uri", f.uri.toString()).put("name", f.name))
        context.getSharedPreferences("folders", Context.MODE_PRIVATE).edit().putString("items", arr.toString()).apply()
    }

    private fun folderName(context: Context, tree: Uri): String {
        return try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: tree.lastPathSegment.orEmpty()
        } catch (_: Exception) {
            tree.lastPathSegment?.substringAfterLast(':').orEmpty()
        }
    }

    /** Klasördeki model ve ZIP dosyaları (alt klasörler dahil, en fazla 4 seviye ve [limit] dosya). */
    fun listFolder(context: Context, tree: Uri, limit: Int = 500): List<FolderFile> {
        val out = ArrayList<FolderFile>()
        val rootId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (_: Exception) {
            return out
        }
        fun walk(docId: String, path: String, depth: Int) {
            if (out.size >= limit || depth > 4) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            val dirs = ArrayList<Pair<String, String>>()
            try {
                context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext() && out.size < limit) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (!name.startsWith(".")) dirs += id to name
                        } else if (isSupported(name)) {
                            out += FolderFile(
                                DocumentsContract.buildDocumentUriUsingTree(tree, id), name,
                                if (c.isNull(3)) -1 else c.getLong(3),
                                if (c.isNull(4)) 0 else c.getLong(4),
                                path,
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                return
            }
            for ((id, name) in dirs) walk(id, if (path.isEmpty()) name else "$path/$name", depth + 1)
        }
        walk(rootId, "", 0)
        out.sortByDescending { it.modified }
        return out
    }
}
