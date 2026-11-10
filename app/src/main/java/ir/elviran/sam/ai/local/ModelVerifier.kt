package ir.elviran.sam.ai.local

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Properties

/**
 * How much the app can honestly say about an installed model file. Only [VERIFIED_OFFICIAL_HASH]
 * means "the SHA-256 of this file equals the official hash pinned in the catalog". A model whose
 * catalog entry has no pinned hash can never be [VERIFIED_OFFICIAL_HASH]; at best it is
 * [UNVERIFIED_NO_OFFICIAL_HASH] (size + GGUF header + local fingerprint checked, authenticity not).
 */
enum class ModelTrust {
    NOT_INSTALLED,
    VERIFIED_OFFICIAL_HASH,
    UNVERIFIED_NO_OFFICIAL_HASH,
    INCOMPLETE,
    CORRUPT
}

enum class VerifyFailure { MISSING, EMPTY, NOT_GGUF, INCOMPLETE, SIZE_IMPLAUSIBLE, HASH_MISMATCH, MODIFIED_AFTER_INSTALL }

sealed interface VerifyOutcome {
    /** [sha256] is the real hash of the file; [matchesOfficialHash] is true only when the catalog pins one and it matched. */
    data class Ok(val sha256: String, val matchesOfficialHash: Boolean) : VerifyOutcome
    data class Failed(val reason: VerifyFailure, val message: String) : VerifyOutcome
}

/** Fingerprint written next to an installed model right after it passed verification. */
data class ModelRecord(val sha256: String, val size: Long, val lastModified: Long, val source: String)

/** Pure file-level model checks (no Android classes) so they can be unit-tested on a plain JVM. */
object ModelVerifier {
    private val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
    private const val LOWER_RATIO = 0.9
    private const val UPPER_RATIO = 1.3

    fun hasGgufMagic(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(4)
            var n = 0
            while (n < 4) { val r = input.read(head, n, 4 - n); if (r < 0) break; n += r }
            n == 4 && head.contentEquals(GGUF_MAGIC)
        }
    }.getOrDefault(false)

    fun sha256Of(file: File, onProgress: (Long) -> Unit = {}): String = file.inputStream().use { sha256Of(it, onProgress) }

    fun sha256Of(input: InputStream, onProgress: (Long) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            md.update(buf, 0, r)
            total += r
            onProgress(total)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** `catalog sha256` must be exactly 64 hex characters or null. */
    fun isValidSha256(s: String?): Boolean = s != null && s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    fun sizeLooksRight(length: Long, spec: LlmModelSpec): Boolean =
        length >= (spec.sizeBytes * LOWER_RATIO).toLong() && length <= (spec.sizeBytes * UPPER_RATIO).toLong()

    /**
     * Full verification of [file] against [spec]: exists, not empty, GGUF header, not truncated
     * (also against [serverLength] when the server announced one), size plausible, and SHA-256 equal
     * to the official hash when the catalog pins one.
     */
    fun verifyFile(file: File, spec: LlmModelSpec, serverLength: Long? = null, onHashProgress: (Long) -> Unit = {}): VerifyOutcome {
        if (!file.isFile) return VerifyOutcome.Failed(VerifyFailure.MISSING, "فایل مدل پیدا نشد")
        val len = file.length()
        if (len <= 0L) return VerifyOutcome.Failed(VerifyFailure.EMPTY, "فایل مدل خالی است")
        if (!hasGgufMagic(file)) return VerifyOutcome.Failed(VerifyFailure.NOT_GGUF, "فایل، یک مدل GGUF معتبر نیست (سرآیند نادرست)")
        if (serverLength != null && len != serverLength) {
            return VerifyOutcome.Failed(VerifyFailure.INCOMPLETE, "فایل ناقص است ($len از $serverLength بایت)")
        }
        if (len < (spec.sizeBytes * LOWER_RATIO).toLong()) {
            return VerifyOutcome.Failed(VerifyFailure.INCOMPLETE, "حجم فایل کمتر از انتظار است؛ احتمالاً ناقص است")
        }
        if (len > (spec.sizeBytes * UPPER_RATIO).toLong()) {
            return VerifyOutcome.Failed(VerifyFailure.SIZE_IMPLAUSIBLE, "حجم فایل بیش از انتظار است؛ فایل این مدل نیست")
        }
        val actual = sha256Of(file, onHashProgress)
        val pinned = spec.sha256
        if (pinned != null) {
            if (!actual.equals(pinned, ignoreCase = true)) {
                return VerifyOutcome.Failed(VerifyFailure.HASH_MISMATCH, "صحت فایل مدل تأیید نشد (SHA-256 با مقدار رسمی یکی نیست)")
            }
            return VerifyOutcome.Ok(actual, matchesOfficialHash = true)
        }
        return VerifyOutcome.Ok(actual, matchesOfficialHash = false)
    }

    // --- sidecar record ("fingerprint") --------------------------------------------------------

    fun writeRecord(recordFile: File, record: ModelRecord) {
        val p = Properties()
        p.setProperty("sha256", record.sha256)
        p.setProperty("size", record.size.toString())
        p.setProperty("lastModified", record.lastModified.toString())
        p.setProperty("source", record.source)
        val tmp = File(recordFile.parentFile, recordFile.name + ".tmp")
        tmp.outputStream().use { p.store(it, null) }
        if (!tmp.renameTo(recordFile)) { tmp.copyTo(recordFile, overwrite = true); tmp.delete() }
    }

    fun readRecord(recordFile: File): ModelRecord? = runCatching {
        if (!recordFile.isFile) return null
        val p = Properties().apply { recordFile.inputStream().use { load(it) } }
        val sha = p.getProperty("sha256") ?: return null
        if (!isValidSha256(sha)) return null
        ModelRecord(sha, p.getProperty("size").toLong(), p.getProperty("lastModified").toLong(), p.getProperty("source") ?: "unknown")
    }.getOrNull()

    /**
     * Strong integrity check for an already-installed file (full re-hash). Detects: missing/empty/non-GGUF
     * file, truncation, a file changed after install (hash differs from the recorded one), and a file that
     * does not match the official hash. When there is no record yet (older install) the file is verified
     * from scratch and a record is written.
     */
    fun verifyInstalled(file: File, recordFile: File, spec: LlmModelSpec, onHashProgress: (Long) -> Unit = {}): VerifyOutcome {
        val outcome = verifyFile(file, spec, null, onHashProgress)
        if (outcome !is VerifyOutcome.Ok) return outcome
        val record = readRecord(recordFile)
        if (record == null) {
            writeRecord(recordFile, ModelRecord(outcome.sha256, file.length(), file.lastModified(), "legacy"))
            return outcome
        }
        if (!record.sha256.equals(outcome.sha256, ignoreCase = true)) {
            return VerifyOutcome.Failed(VerifyFailure.MODIFIED_AFTER_INSTALL, "فایل مدل پس از نصب تغییر کرده یا خراب شده است")
        }
        if (record.size != file.length() || record.lastModified != file.lastModified()) {
            writeRecord(recordFile, record.copy(size = file.length(), lastModified = file.lastModified()))
        }
        return outcome
    }

    /**
     * Cheap status for the UI (no hashing): size, header and fingerprint (size + mtime) only. Never
     * returns VERIFIED_OFFICIAL_HASH unless a record exists whose hash equals the catalog's official hash
     * and the file still has the recorded size and modification time.
     */
    fun quickTrust(file: File, recordFile: File, spec: LlmModelSpec): ModelTrust {
        if (!file.isFile) return ModelTrust.NOT_INSTALLED
        val len = file.length()
        if (len <= 0L || !hasGgufMagic(file)) return ModelTrust.CORRUPT
        if (len < (spec.sizeBytes * LOWER_RATIO).toLong()) return ModelTrust.INCOMPLETE
        if (len > (spec.sizeBytes * UPPER_RATIO).toLong()) return ModelTrust.CORRUPT
        val pinned = spec.sha256
        val record = readRecord(recordFile)
        if (pinned != null && record != null && record.sha256.equals(pinned, ignoreCase = true) &&
            record.size == len && record.lastModified == file.lastModified()
        ) return ModelTrust.VERIFIED_OFFICIAL_HASH
        return ModelTrust.UNVERIFIED_NO_OFFICIAL_HASH
    }
}
