package ir.elviran.sam

import ir.elviran.sam.ai.local.LlmModelCatalog
import ir.elviran.sam.ai.local.LlmModelSpec
import ir.elviran.sam.ai.local.ModelRecord
import ir.elviran.sam.ai.local.ModelTrust
import ir.elviran.sam.ai.local.ModelVerifier
import ir.elviran.sam.ai.local.VerifyFailure
import ir.elviran.sam.ai.local.VerifyOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * File-level model checks: GGUF header, truncation, size, SHA-256 (official hash pinned or not),
 * modification after install, and the honest trust level shown to the user. Real temp files, no Android.
 */
class ModelVerifierTest {
    private val dir: File = Files.createTempDirectory("sam-llm-test").toFile()
    @After fun cleanup() { dir.deleteRecursively() }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A fake "model": GGUF magic followed by deterministic bytes. */
    private fun modelBytes(size: Int, seed: Int = 7): ByteArray =
        ByteArray(size) { i -> if (i < 4) "GGUF"[i].code.toByte() else ((i * 31 + seed) and 0xFF).toByte() }

    private fun spec(size: Long, sha: String? = null) = LlmModelSpec(
        id = "m", displayName = "M", url = "https://example.com/m.gguf", sizeBytes = size,
        quant = "Q4_K_M", paramsB = 1.0, minRamMb = 1, recommendedContext = 4096, sha256 = sha
    )

    private fun write(name: String, bytes: ByteArray) = File(dir, name).also { it.writeBytes(bytes) }

    // ---------------- verifyFile ----------------

    @Test fun missing_empty_and_non_gguf_files_are_rejected() {
        val s = spec(1000)
        assertEquals(VerifyFailure.MISSING, (ModelVerifier.verifyFile(File(dir, "nope"), s) as VerifyOutcome.Failed).reason)
        assertEquals(VerifyFailure.EMPTY, (ModelVerifier.verifyFile(write("e", ByteArray(0)), s) as VerifyOutcome.Failed).reason)
        val html = write("h", "<html>Access denied</html>".toByteArray().copyOf(1000))
        assertEquals(VerifyFailure.NOT_GGUF, (ModelVerifier.verifyFile(html, s) as VerifyOutcome.Failed).reason)
    }

    @Test fun truncated_download_is_detected_by_size_and_by_server_length() {
        val full = modelBytes(1000)
        val truncated = write("t", full.copyOf(500))
        assertEquals(VerifyFailure.INCOMPLETE, (ModelVerifier.verifyFile(truncated, spec(1000)) as VerifyOutcome.Failed).reason)
        // plausible by catalog size, but shorter than what the server announced
        val almost = write("a", full.copyOf(980))
        assertEquals(VerifyFailure.INCOMPLETE, (ModelVerifier.verifyFile(almost, spec(1000), serverLength = 1000L) as VerifyOutcome.Failed).reason)
    }

    @Test fun a_file_far_larger_than_the_catalog_size_is_rejected() {
        val big = write("b", modelBytes(5000))
        assertEquals(VerifyFailure.SIZE_IMPLAUSIBLE, (ModelVerifier.verifyFile(big, spec(1000)) as VerifyOutcome.Failed).reason)
    }

    @Test fun pinned_hash_match_is_verified_and_mismatch_is_rejected() {
        val bytes = modelBytes(1000)
        val f = write("ok", bytes)
        val ok = ModelVerifier.verifyFile(f, spec(1000, sha(bytes))) as VerifyOutcome.Ok
        assertTrue(ok.matchesOfficialHash); assertEquals(sha(bytes), ok.sha256)
        assertTrue("hash comparison ignores case", ModelVerifier.verifyFile(f, spec(1000, sha(bytes).uppercase())) is VerifyOutcome.Ok)

        val corrupted = bytes.copyOf().also { it[500] = (it[500] + 1).toByte() } // one flipped byte, same size
        val bad = write("bad", corrupted)
        val r = ModelVerifier.verifyFile(bad, spec(1000, sha(bytes))) as VerifyOutcome.Failed
        assertEquals(VerifyFailure.HASH_MISMATCH, r.reason)
    }

    @Test fun without_an_official_hash_the_file_is_accepted_but_never_marked_as_matching() {
        val f = write("u", modelBytes(1000))
        val ok = ModelVerifier.verifyFile(f, spec(1000, null)) as VerifyOutcome.Ok
        assertFalse(ok.matchesOfficialHash)
    }

    @Test fun hashing_streams_without_loading_the_whole_file() {
        val bytes = ByteArray(3 * 1024 * 1024 + 17) { (it % 251).toByte() }
        val f = write("big", bytes)
        assertEquals(sha(bytes), ModelVerifier.sha256Of(f))
        assertEquals(sha(bytes), ModelVerifier.sha256Of(bytes.inputStream())) // import path hashes a stream the same way
    }

    // ---------------- record + install-time fingerprint ----------------

    @Test fun record_roundtrip_and_garbage_record_is_ignored() {
        val rec = File(dir, "r.meta")
        val r = ModelRecord(sha("x".toByteArray()), 1234, 99999, "download")
        ModelVerifier.writeRecord(rec, r)
        assertEquals(r, ModelVerifier.readRecord(rec))
        rec.writeText("not a record")
        assertNull(ModelVerifier.readRecord(rec))
        assertNull(ModelVerifier.readRecord(File(dir, "absent.meta")))
    }

    @Test fun installed_file_modified_after_download_is_detected() {
        val bytes = modelBytes(1000)
        val f = write("inst.gguf", bytes); val rec = File(dir, "inst.gguf.meta")
        val s = spec(1000)
        assertTrue(ModelVerifier.verifyInstalled(f, rec, s) is VerifyOutcome.Ok) // creates the record (older installs)
        assertTrue(ModelVerifier.verifyInstalled(f, rec, s) is VerifyOutcome.Ok) // unchanged -> still OK

        f.writeBytes(bytes.copyOf().also { it[700] = (it[700] + 1).toByte() })    // tampered / bit-rotted, same size
        val r = ModelVerifier.verifyInstalled(f, rec, s) as VerifyOutcome.Failed
        assertEquals(VerifyFailure.MODIFIED_AFTER_INSTALL, r.reason)
    }

    @Test fun touching_the_file_without_changing_content_is_not_a_failure() {
        val f = write("t.gguf", modelBytes(1000)); val rec = File(dir, "t.gguf.meta")
        val s = spec(1000)
        ModelVerifier.verifyInstalled(f, rec, s)
        f.setLastModified(f.lastModified() + 60_000)
        assertTrue(ModelVerifier.verifyInstalled(f, rec, s) is VerifyOutcome.Ok)
        assertEquals(f.lastModified(), ModelVerifier.readRecord(rec)!!.lastModified)
    }

    @Test fun installed_file_truncated_after_download_is_detected() {
        val f = write("tr.gguf", modelBytes(1000)); val rec = File(dir, "tr.gguf.meta")
        val s = spec(1000)
        ModelVerifier.verifyInstalled(f, rec, s)
        f.writeBytes(modelBytes(1000).copyOf(300))
        assertTrue(ModelVerifier.verifyInstalled(f, rec, s) is VerifyOutcome.Failed)
    }

    @Test fun installed_file_not_matching_a_later_pinned_official_hash_is_rejected() {
        val bytes = modelBytes(1000)
        val f = write("p.gguf", bytes); val rec = File(dir, "p.gguf.meta")
        ModelVerifier.verifyInstalled(f, rec, spec(1000, null)) // installed while no official hash existed
        val pinnedOther = sha("some other file".toByteArray())
        val r = ModelVerifier.verifyInstalled(f, rec, spec(1000, pinnedOther)) as VerifyOutcome.Failed
        assertEquals(VerifyFailure.HASH_MISMATCH, r.reason)
    }

    // ---------------- honest trust level ----------------

    @Test fun trust_is_verified_only_with_a_matching_official_hash_and_an_unchanged_file() {
        val bytes = modelBytes(1000)
        val f = write("v.gguf", bytes); val rec = File(dir, "v.gguf.meta")
        val pinned = spec(1000, sha(bytes))
        val unpinned = spec(1000, null)

        assertEquals(ModelTrust.NOT_INSTALLED, ModelVerifier.quickTrust(File(dir, "none.gguf"), rec, pinned))
        ModelVerifier.verifyInstalled(f, rec, pinned)
        assertEquals(ModelTrust.VERIFIED_OFFICIAL_HASH, ModelVerifier.quickTrust(f, rec, pinned))
        assertEquals("no official hash -> never 'verified'", ModelTrust.UNVERIFIED_NO_OFFICIAL_HASH, ModelVerifier.quickTrust(f, rec, unpinned))

        f.setLastModified(f.lastModified() + 5000) // fingerprint changed -> must not claim verified until re-checked
        assertEquals(ModelTrust.UNVERIFIED_NO_OFFICIAL_HASH, ModelVerifier.quickTrust(f, rec, pinned))
    }

    @Test fun trust_reports_incomplete_and_corrupt_files() {
        val rec = File(dir, "q.meta")
        assertEquals(ModelTrust.INCOMPLETE, ModelVerifier.quickTrust(write("i.gguf", modelBytes(500)), rec, spec(1000)))
        assertEquals(ModelTrust.CORRUPT, ModelVerifier.quickTrust(write("c.gguf", ByteArray(1000)), rec, spec(1000)))
    }

    // ---------------- catalog honesty ----------------

    @Test fun catalog_hashes_are_either_absent_or_well_formed_64_hex() {
        LlmModelCatalog.models.forEach { m ->
            assertTrue("sha256 of ${m.id}", m.sha256 == null || ModelVerifier.isValidSha256(m.sha256))
            assertEquals(m.sha256 != null, m.hasOfficialHash)
        }
        assertFalse(ModelVerifier.isValidSha256("abc"))
        assertFalse(ModelVerifier.isValidSha256("z".repeat(64)))
        assertTrue(ModelVerifier.isValidSha256("a".repeat(64)))
        assertNotNull(LlmModelCatalog.default)
    }

    @Test fun a_catalog_model_without_an_official_hash_can_never_be_reported_as_verified() {
        LlmModelCatalog.models.filter { !it.hasOfficialHash }.forEach { m ->
            val f = write("${m.id}.gguf", modelBytes(16)); val rec = File(dir, "${m.id}.meta")
            ModelVerifier.writeRecord(rec, ModelRecord(sha(f.readBytes()), f.length(), f.lastModified(), "download"))
            val t = ModelVerifier.quickTrust(f, rec, m.copy(sizeBytes = 16))
            assertTrue("${m.id}: $t", t != ModelTrust.VERIFIED_OFFICIAL_HASH)
        }
    }
}
