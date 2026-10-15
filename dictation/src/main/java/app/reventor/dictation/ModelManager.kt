package app.reventor.dictation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the Parakeet model files (~670 MB total, NVIDIA CC-BY-4.0) into filesDir
 * on first use. The four int8 ONNX files come from the official sherpa-onnx export.
 */
object ModelManager {
    private const val TAG = "ReventorDictation"

    private const val PARAKEET_BASE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/"
    private val PARAKEET_FILES = listOf(
        "tokens.txt",
        "joiner.int8.onnx",
        "decoder.int8.onnx",
        "encoder.int8.onnx",
    )
    private val PARAKEET_TOTAL_BYTES = 652_184_281L + 11_845_275L + 6_355_277L + 93_939L

    @Volatile
    private var downloading = false

    /** Download progress in percent, or -1 when not downloading. */
    @Volatile
    var parakeetProgress: Int = -1
        private set

    fun parakeetModelDir(context: Context): File {
        val dir = File(context.filesDir, "models/parakeet-v3-int8")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** True when all model files are present at their expected sizes. */
    fun parakeetComplete(context: Context): Boolean {
        val dir = parakeetModelDir(context)
        val encoder = File(dir, "encoder.int8.onnx")
        if (encoder.length() < 600L * 1024 * 1024) return false
        return PARAKEET_FILES.all { File(dir, it).length() > 0 }
    }

    fun isDownloading(): Boolean = downloading

    /** Returns false when a download is already running. */
    fun startDownload(context: Context): Boolean {
        if (downloading) return false
        downloading = true
        parakeetProgress = 0
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            var doneBytes = 0L
            try {
                val dir = parakeetModelDir(appContext)
                outer@ for (name in PARAKEET_FILES) {
                    val target = File(dir, name)
                    if (target.length() > 0) continue
                    val part = File(dir, "$name.part")
                    var conn: HttpURLConnection? = null
                    try {
                        conn = URL(PARAKEET_BASE + name).openConnection() as HttpURLConnection
                        conn.instanceFollowRedirects = true
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) reventor-keyboard")
                        conn.connectTimeout = 15_000
                        conn.readTimeout = 60_000
                        conn.getInputStream().use { input ->
                            part.outputStream().use { output ->
                                val buf = ByteArray(256 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    doneBytes += n
                                    parakeetProgress = (doneBytes * 100 / PARAKEET_TOTAL_BYTES).toInt().coerceIn(0, 99)
                                }
                            }
                        }
                        if (part.length() == 0L) break@outer
                        if (target.exists()) target.delete()
                        part.renameTo(target)
                    } finally {
                        conn?.disconnect()
                    }
                }
                if (parakeetComplete(appContext)) {
                    parakeetProgress = 100
                    main.post { toast(appContext, "Dictation engine ready") }
                } else {
                    parakeetProgress = -1
                    main.post { toast(appContext, "Dictation engine download failed") }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "model download failed", t)
                parakeetProgress = -1
                main.post { toast(appContext, "Dictation engine download failed: ${t.message}") }
            } finally {
                downloading = false
            }
        }.start()
        return true
    }

    private fun toast(context: Context, message: String) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }
}
