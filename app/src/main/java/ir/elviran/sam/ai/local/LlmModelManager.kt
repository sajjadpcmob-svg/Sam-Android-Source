package ir.elviran.sam.ai.local

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

/**
 * Downloads, checks, stores and removes the on-device GGUF model file. The heavy model is never part
 * of the APK; it is fetched over HTTPS into app-private storage and then runs fully offline.
 *
 * Integrity rules (all file-level logic lives in [ModelVerifier]):
 *  - A file only becomes "installed" after it passed [ModelVerifier.verifyFile]; until then it is a
 *    `.part` file. The final rename is atomic, so a half-written model is never visible as installed.
 *  - Downloads resume from the `.part` file (HTTP Range); a download that ends early is reported as
 *    incomplete and keeps its `.part`; a file that fails verification is deleted.
 *  - A model with an official SHA-256 in the catalog must match it (download, import and before every
 *    load). A model without one is NEVER reported as verified ([ModelTrust.UNVERIFIED_NO_OFFICIAL_HASH]).
 *  - After install a fingerprint (hash, size, mtime) is stored next to the model, so a file that is
 *    changed afterwards is detected by the full re-check done before loading ([ensureIntegrity]).
 */
class LlmModelManager(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "llm").apply { mkdirs() }

    fun fileFor(spec: LlmModelSpec): File = File(dir, "${spec.id}.gguf")
    private fun recordFor(spec: LlmModelSpec): File = File(dir, "${spec.id}.gguf.meta")
    private fun partFor(spec: LlmModelSpec): File = File(dir, "${spec.id}.part")

    /** Cheap "is a plausible complete GGUF file present" check (no hashing). */
    fun isInstalled(spec: LlmModelSpec): Boolean {
        val f = fileFor(spec)
        return f.isFile && f.length() >= (spec.sizeBytes * 0.9).toLong() && ModelVerifier.hasGgufMagic(f)
    }

    fun installedPath(spec: LlmModelSpec): String? = fileFor(spec).takeIf { it.isFile }?.absolutePath

    /** Status for the UI; never hashes (fast). */
    fun trust(spec: LlmModelSpec): ModelTrust {
        val t = ModelVerifier.quickTrust(fileFor(spec), recordFor(spec), spec)
        if (t == ModelTrust.NOT_INSTALLED && partFor(spec).isFile) return ModelTrust.INCOMPLETE
        return t
    }

    /** Total device RAM in MB, for the fit check before download/load. */
    fun deviceRamMb(): Int {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        return (mi.totalMem / (1024 * 1024)).toInt()
    }

    /**
     * Full re-check of the installed file (hash + header + size + fingerprint). Call before loading it.
     * Returns null when OK, otherwise a Persian error; a corrupt or modified file is deleted so that it
     * cannot be loaded and can be downloaded again.
     */
    suspend fun ensureIntegrity(spec: LlmModelSpec, onHashProgress: (Long) -> Unit = {}): String? = withContext(Dispatchers.IO) {
        when (val r = ModelVerifier.verifyInstalled(fileFor(spec), recordFor(spec), spec, onHashProgress)) {
            is VerifyOutcome.Ok -> null
            is VerifyOutcome.Failed -> {
                if (r.reason != VerifyFailure.MISSING) uninstall(spec)
                r.message
            }
        }
    }

    /**
     * Imports a GGUF the user already has (any [InputStream], e.g. from the system file picker). The
     * bytes are copied to a `.part` file, fully verified, and only then installed. Returns null on
     * success or a Persian error. [expectedBytes] (if known) is used for a disk-space check.
     */
    suspend fun importModel(spec: LlmModelSpec, input: InputStream, expectedBytes: Long = -1L, onProgress: (Int) -> Unit = {}): String? =
        withContext(Dispatchers.IO) {
            val part = partFor(spec)
            try {
                if (expectedBytes > 0 && dir.usableSpace < expectedBytes + SPACE_MARGIN) return@withContext "فضای خالی دستگاه برای وارد کردن مدل کافی نیست"
                part.delete()
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(1 shl 16); var copied = 0L; var r: Int
                    while (input.read(buf).also { r = it } != -1) {
                        coroutineContext.ensureActive()
                        out.write(buf, 0, r); copied += r
                        if (expectedBytes > 0) onProgress(((copied * 90) / expectedBytes).toInt().coerceIn(0, 90))
                    }
                }
                onProgress(92)
                when (val v = ModelVerifier.verifyFile(part, spec)) {
                    is VerifyOutcome.Failed -> { part.delete(); v.message }
                    is VerifyOutcome.Ok -> install(spec, part, v, "import").also { onProgress(100) }
                }
            } catch (e: Throwable) {
                runCatching { part.delete() }
                if (e is kotlinx.coroutines.CancellationException) throw e
                "خطا در وارد کردن مدل: ${e.message ?: "نامشخص"}"
            }
        }

    /** Convenience for a file path (copies via [importModel], so the same verification applies). */
    suspend fun importFrom(spec: LlmModelSpec, source: File): String? {
        if (!source.isFile || source.length() <= 0) return "فایل مدل معتبر نیست"
        return source.inputStream().use { importModel(spec, it, source.length()) }
    }

    /**
     * Downloads [spec] with progress 0..100, resuming a previous partial download. Idempotent (an
     * installed, intact model is not downloaded again). Returns null on success or a Persian error.
     */
    suspend fun download(spec: LlmModelSpec, progress: (Int) -> Unit = {}): String? = withContext(Dispatchers.IO) {
        if (isInstalled(spec) && recordFor(spec).isFile && trust(spec) != ModelTrust.CORRUPT) { progress(100); return@withContext null }
        if (!spec.fitsRam(deviceRamMb())) return@withContext "حافظه دستگاه برای این مدل کافی نیست (حداقل ${spec.minRamMb} مگابایت)"
        val part = partFor(spec)
        val have = if (part.isFile) part.length() else 0L
        val remaining = (spec.sizeBytes * 1.05).toLong() - have
        if (dir.usableSpace < remaining + SPACE_MARGIN) return@withContext "فضای خالی دستگاه برای دانلود مدل کافی نیست"

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000; readTimeout = 60_000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SAM-Android")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = conn.responseCode
            var resumeFrom = 0L
            var expectedTotal: Long? = null
            when (code) {
                200 -> { // server ignored Range (or fresh start): start over
                    resumeFrom = 0L
                    expectedTotal = conn.contentLengthLong.takeIf { it > 0 }
                }
                206 -> {
                    resumeFrom = have
                    expectedTotal = parseTotalFromContentRange(conn.getHeaderField("Content-Range"))
                        ?: conn.contentLengthLong.takeIf { it > 0 }?.let { it + have }
                }
                416 -> { part.delete(); return@withContext "فایل نیمه‌کاره نامعتبر بود و پاک شد؛ دوباره دانلود را بزن" }
                else -> return@withContext "دانلود مدل ناموفق بود: HTTP $code"
            }
            val total = expectedTotal ?: spec.sizeBytes
            var read = resumeFrom
            conn.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { raf ->
                    raf.setLength(resumeFrom); raf.seek(resumeFrom)
                    val buf = ByteArray(1 shl 16); var r: Int
                    while (input.read(buf).also { r = it } != -1) {
                        coroutineContext.ensureActive()
                        raf.write(buf, 0, r); read += r
                        progress(((read * 90) / total).toInt().coerceIn(0, 90))
                    }
                }
            }
            // The stream ended: was the whole file delivered?
            if (expectedTotal != null && part.length() != expectedTotal) {
                return@withContext "دانلود ناقص ماند (${part.length()} از $expectedTotal بایت)؛ دوباره بزن تا ادامه یابد"
            }
            progress(92)
            when (val v = ModelVerifier.verifyFile(part, spec, expectedTotal) { h -> progress((92 + (h * 7 / part.length().coerceAtLeast(1))).toInt().coerceIn(92, 99)) }) {
                is VerifyOutcome.Failed -> { part.delete(); v.message }
                is VerifyOutcome.Ok -> install(spec, part, v, "download").also { if (it == null) progress(100) }
            }
        } catch (e: Throwable) {
            // Keep the .part so the next attempt can resume; it is verified again before install anyway.
            if (e is kotlinx.coroutines.CancellationException) throw e
            "دانلود ناقص ماند: ${e.message ?: "قطع ارتباط"}؛ دوباره بزن تا ادامه یابد"
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    fun uninstall(spec: LlmModelSpec) {
        runCatching { fileFor(spec).delete() }
        runCatching { recordFor(spec).delete() }
        runCatching { partFor(spec).delete() }
    }

    /** Atomically moves a verified `.part` into place and writes its fingerprint. */
    private fun install(spec: LlmModelSpec, part: File, ok: VerifyOutcome.Ok, source: String): String? {
        val target = fileFor(spec)
        val record = recordFor(spec)
        runCatching { record.delete() } // never keep a fingerprint that belongs to a replaced file
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            return "نصب مدل ناموفق بود: ${e.message ?: "انتقال فایل"}"
        }
        ModelVerifier.writeRecord(record, ModelRecord(ok.sha256, target.length(), target.lastModified(), source))
        return null
    }

    private fun parseTotalFromContentRange(header: String?): Long? =
        header?.substringAfterLast('/')?.trim()?.toLongOrNull()

    companion object {
        private const val SPACE_MARGIN = 200L * 1024 * 1024
    }
}
