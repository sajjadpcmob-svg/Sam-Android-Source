package ir.elviran.sam

import ir.elviran.sam.ai.local.LlmModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Extra catalog invariants (the original LlmModelCatalogTest is kept unchanged). */
class LlmCatalogIntegrityTest {
    @Test fun ids_are_unique_and_urls_point_at_gguf_files() {
        val ids = LlmModelCatalog.models.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        LlmModelCatalog.models.forEach { assertTrue("${it.id} url", it.url.startsWith("https://") && it.url.endsWith(".gguf")) }
    }

    @Test fun default_model_is_qwen25_instruct_with_the_chatml_template() {
        assertTrue(LlmModelCatalog.default.id.startsWith("qwen2.5"))
        assertEquals("chatml-qwen25", LlmModelCatalog.default.templateId)
    }
}
