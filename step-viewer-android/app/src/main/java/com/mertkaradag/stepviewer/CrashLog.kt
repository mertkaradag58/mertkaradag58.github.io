package com.mertkaradag.stepviewer

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Çökme raporu: yakalanmamış hata yığını dosyaya yazılır; uygulama bir sonraki açılışta
 * kullanıcıya gösterir ve paylaşmasını önerir. Yerel (C++) çökmeler Android 11+ üzerinde
 * ApplicationExitInfo ile bulunur. Hiçbir şey otomatik gönderilmez.
 */
object CrashLog {

    private const val FILE = "last_crash.txt"
    private const val PREFS = "crash"
    private const val KEY_EXIT_SEEN = "exit_seen"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                save(app, "Thread: ${thread.name}\n" + trace(e))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Çökmeyen ama yakalanan önemli bir hatayı da rapora yazar (ör. AR çizim hatası). */
    fun record(context: Context, where: String, e: Throwable) {
        try {
            save(context.applicationContext, "Caught in $where\n" + trace(e))
        } catch (_: Throwable) {
        }
    }

    private fun trace(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun header(context: Context, time: Long): String {
        val version = try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            "${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()})"
        } catch (_: Throwable) {
            "?"
        }
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))
        return "Cepte CAD $version\n" +
            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "$date\n\n"
    }

    private fun save(context: Context, body: String) {
        File(context.filesDir, FILE).writeText(header(context, System.currentTimeMillis()) + body.take(60_000))
    }

    /** Bekleyen bir çökme raporu varsa gösterir (bir kez). */
    fun showPendingReport(activity: AppCompatActivity) {
        val report = takeReport(activity) ?: return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.crash_title)
            .setMessage(R.string.crash_message)
            .setPositiveButton(R.string.crash_send) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Cepte CAD crash report")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                try {
                    activity.startActivity(Intent.createChooser(send, activity.getString(R.string.crash_send)))
                } catch (_: Throwable) {
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun takeReport(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var report: String? = null
        val f = File(context.filesDir, FILE)
        if (f.exists()) {
            report = try { f.readText() } catch (_: Throwable) { null }
            f.delete()
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val am = context.getSystemService(ActivityManager::class.java)
                val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
                val seen = prefs.getLong(KEY_EXIT_SEEN, 0L)
                if (last != null && last.timestamp > seen) {
                    prefs.edit().putLong(KEY_EXIT_SEEN, last.timestamp).apply()
                    // Java çökmesinin yığını zaten dosyada; yerel çökme ve ANR'yi ayrıca ekle
                    val kind = when (last.reason) {
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                        ApplicationExitInfo.REASON_ANR -> "ANR (not responding)"
                        ApplicationExitInfo.REASON_CRASH -> if (report == null) "Crash" else null
                        else -> null
                    }
                    if (kind != null && System.currentTimeMillis() - last.timestamp < 86_400_000L) {
                        val extra = "$kind: ${last.description ?: ""}\n" +
                            "pss=${last.pss} kB, rss=${last.rss} kB, importance=${last.importance}\n"
                        report = (report ?: header(context, last.timestamp)) + "\n" + extra
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return report
    }
}
