package ir.elviran.sam

import ir.elviran.sam.ai.local.LlmModelCatalog
import ir.elviran.sam.ai.local.LlmModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure, Android-free tests for the on-device model catalog. They lock the RAM fit-check and the
 * lookup helpers so a device with too little memory can never be offered a model it cannot load.
 */
class LlmModelCatalogTest {

    private val sample = LlmModelSpec(
        id = "test-model", displayName = "Test", url = "https://example.com/m.gguf",
        sizeBytes = 2_000_000_000L, quant = "Q4_K_M", paramsB = 3.0,
        minRamMb = 6_000, recommendedContext = 4096, sha256 = null
    )

    @Test fun fitsRam_true_when_device_has_enough() {
        assertTrue(sample.fitsRam(12_000))
        assertTrue(sample.fitsRam(6_000)) // exactly the minimum must pass
    }

    @Test fun fitsRam_false_when_device_too_small() {
        assertFalse(sample.fitsRam(5_999))
        assertFalse(sample.fitsRam(0))
    }

    @Test fun catalog_is_non_empty_and_default_is_first() {
        assertTrue(LlmModelCatalog.models.isNotEmpty())
        assertEquals(LlmModelCatalog.models.first().id, LlmModelCatalog.default.id)
    }

    @Test fun byId_resolves_known_and_rejects_unknown() {
        val first = LlmModelCatalog.models.first()
        assertNotNull(LlmModelCatalog.byId(first.id))
        assertEquals(first.id, LlmModelCatalog.byId(first.id)?.id)
        assertNull(LlmModelCatalog.byId("does-not-exist"))
        assertNull(LlmModelCatalog.byId(null))
    }

    @Test fun every_catalog_model_has_https_url_and_sane_size() {
        LlmModelCatalog.models.forEach { m ->
            assertTrue("url must be https for ${m.id}", m.url.startsWith("https://"))
            assertTrue("size must be positive for ${m.id}", m.sizeBytes > 0)
            assertTrue("minRam must be positive for ${m.id}", m.minRamMb > 0)
        }
    }
}
