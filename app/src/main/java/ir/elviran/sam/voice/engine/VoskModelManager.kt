package ir.elviran.sam.voice.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Manages the offline Persian Vosk model used for BOTH wake-word spotting and offline dictation.
 *
 * The model is NOT bundled in the APK (it is ~50 MB and Apache-2.0 licensed); the user downloads it
 * once from the official Vosk model index over HTTPS, after which everything works with no network.
 * The model is extracted into app-private storage; no raw audio ever leaves the device.
 *
 * Default: vosk-model-small-fa-0.5 (small, fast, fits comfortably in 12 GB RAM, good for S24 Ultra).
 */
class VoskModelManager(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "vosk")
    private val modelDir = File(root, "model-fa")

    /** Official small Persian model (Apache-2.0). Replaceable from settings if the user prefers. */
    var modelUrl: String = "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.5.zip"

    @Volatile private var loaded: Model? = null

    fun isInstalled(): Boolean = modelDir.isDirectory && File(modelDir, "am").exists()

    /** Returns a loaded [Model], loading it from disk on first use. Null if not installed. */
    suspend fun model(): Model? = withContext(Dispatchers.IO) {
        loaded?.let { return@withContext it }
        if (!isInstalled()) return@withContext null
        runCatching { Model(modelDir.absolutePath) }.getOrNull()?.also { loaded = it }
    }

    /**
     * Downloads + extracts the model with progress (0..100). Idempotent: skips if already installed.
     * @return null on success, or a Persian error message.
     */
    suspend fun install(progress: (Int) -> Unit = {}): String? = withContext(Dispatchers.IO) {
        if (isInstalled()) { progress(100); return@withContext null }
        root.mkdirs()
        val zip = File(root, "model.zip")
        try {
            val conn = (URL(modelUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000; readTimeout = 60_000; instanceFollowRedirects = true
            }
            if (conn.responseCode / 100 != 2) return@withContext "دانلود مدل ناموفق بود: HTTP ${conn.responseCode}"
            val total = conn.contentLengthLong.coerceAtLeast(1L)
            conn.inputStream.use { input ->
                FileOutputStream(zip).use { out ->
                    val buf = ByteArray(1 shl 16); var read = 0L; var r: Int
                    while (input.read(buf).also { r = it } != -1) {
                        out.write(buf, 0, r); read += r
                        progress(((read * 60) / total).toInt().coerceIn(0, 60))
                    }
                }
            }
            val tmp = File(root, "extract"); tmp.deleteRecursively(); tmp.mkdirs()
            ZipInputStream(zip.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(tmp, entry.name)
                    if (!outFile.canonicalPath.startsWith(tmp.canonicalPath)) return@withContext "فایل مدل نامعتبر است"
                    if (entry.isDirectory) outFile.mkdirs() else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { zis.copyTo(it) }
                    }
                    zis.closeEntry(); entry = zis.nextEntry
                }
            }
            progress(85)
            // the zip wraps a single top folder; move its contents to modelDir
            val inner = tmp.listFiles()?.firstOrNull { it.isDirectory } ?: tmp
            modelDir.deleteRecursively()
            if (!inner.renameTo(modelDir)) inner.copyRecursively(modelDir, overwrite = true)
            tmp.deleteRecursively(); zip.delete()
            progress(100)
            if (isInstalled()) null else "ساختار مدل ناشناخته است"
        } catch (e: Throwable) {
            runCatching { zip.delete() }
            "خطا در نصب مدل آفلاین: ${e.message ?: "نامشخص"}"
        }
    }

    fun uninstall() { loaded?.let { runCatching { it.close() } }; loaded = null; root.deleteRecursively() }
    fun release() { loaded?.let { runCatching { it.close() } }; loaded = null }
}
