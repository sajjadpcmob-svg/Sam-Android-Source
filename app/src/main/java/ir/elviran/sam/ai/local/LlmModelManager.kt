package ir.elviran.sam.ai.local

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads, verifies, stores and removes the on-device GGUF model file. The heavy model is never
 * part of the APK (spec rule 13); it is fetched once over HTTPS into app-private storage and then
 * runs fully offline. Supports importing an already-present file by path (spec rule 12).
 *
 * Verification: byte-size is always checked; when the catalog pins a [LlmModelSpec.sha256] the file
 * hash is enforced and a mismatch deletes the file. No raw model bytes ever leave the device.
 */
class LlmModelManager(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "llm").apply { mkdirs() }

    fun fileFor(spec: LlmModelSpec): File = File(dir, "${spec.id}.gguf")

    fun isInstalled(spec: LlmModelSpec): Boolean {
        val f = fileFor(spec)
        return f.isFile && f.length() > 0 && f.length() >= (spec.sizeBytes * 0.9).toLong()
    }

    fun installedPath(spec: LlmModelSpec): String? = fileFor(spec).takeIf { it.isFile }?.absolutePath

    /** Total device RAM in MB, for the fit check before download/load. */
    fun deviceRamMb(): Int {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        return (mi.totalMem / (1024 * 1024)).toInt()
    }

    /**
     * Import a GGUF the user already has on the device by copying it into app storage. Returns null
     * on success or a Persian error. Only accepts a readable file with a plausible size.
     */
    suspend fun importFrom(spec: LlmModelSpec, source: File): String? = withContext(Dispatchers.IO) {
        if (!source.isFile || source.length() <= 0) return@withContext "فایل مدل معتبر نیست"
        runCatching { source.copyTo(fileFor(spec), overwrite = true) }
            .fold({ if (isInstalled(spec)) null else "حجم فایل مدل ناسازگار است" }, { "خطا در کپی مدل: ${it.message}" })
    }

    /**
     * Downloads [spec] with progress 0..100. Idempotent (skips if already installed). Verifies size
     * and, when pinned, SHA-256. Returns null on success or a Persian error message.
     */
    suspend fun download(spec: LlmModelSpec, progress: (Int) -> Unit = {}): String? = withContext(Dispatchers.IO) {
        if (isInstalled(spec)) { progress(100); return@withContext null }
        if (!spec.fitsRam(deviceRamMb())) return@withContext "حافظه دستگاه برای این مدل کافی نیست (حداقل ${spec.minRamMb} مگابایت)"
        val target = fileFor(spec)
        val part = File(dir, "${spec.id}.part")
        try {
            val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000; readTimeout = 60_000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SAM-Android")
            }
            if (conn.responseCode / 100 != 2) return@withContext "دانلود مدل ناموفق بود: HTTP ${conn.responseCode}"
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: spec.sizeBytes
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(1 shl 16); var read = 0L; var r: Int
                    while (input.read(buf).also { r = it } != -1) {
                        out.write(buf, 0, r); read += r
                        progress(((read * 95) / total).toInt().coerceIn(0, 95))
                    }
                }
            }
            // size sanity check
            if (part.length() < (spec.sizeBytes * 0.9).toLong()) {
                part.delete(); return@withContext "حجم فایل دانلود‌شده ناقص است"
            }
            // optional hash check
            spec.sha256?.let { expected ->
                progress(97)
                val actual = sha256Of(part)
                if (!actual.equals(expected, ignoreCase = true)) {
                    part.delete(); return@withContext "صحت فایل مدل تأیید نشد (هش ناهماهنگ)"
                }
            }
            if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
            progress(100)
            if (isInstalled(spec)) null else "نصب مدل ناموفق بود"
        } catch (e: Throwable) {
            runCatching { part.delete() }
            "خطا در دانلود مدل: ${e.message ?: "نامشخص"}"
        }
    }

    fun uninstall(spec: LlmModelSpec) { runCatching { fileFor(spec).delete() } }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16); var r: Int
            while (input.read(buf).also { r = it } != -1) md.update(buf, 0, r)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
