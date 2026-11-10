package ir.elviran.sam

import ir.elviran.sam.ai.ChatTurn
import ir.elviran.sam.ai.local.ChatMLTemplate
import ir.elviran.sam.ai.local.LlamaBackend
import ir.elviran.sam.ai.local.LlmSession
import ir.elviran.sam.ai.local.LlmSession.ChatResult
import ir.elviran.sam.ai.local.LlmSession.FailureReason
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Tests the session logic (KV reset per request, serialisation, stop, release, prompt fitting) with a
 * fake backend that models the native contract and fails loudly on misuse (use-after-free, overlapping
 * generations, a KV cache that was not reset). They do NOT exercise real llama.cpp: that part is only
 * verifiable on a device.
 */
class LlmSessionTest {

    /** Fake of the native runtime. Mirrors the contract documented on LlamaNative. */
    private class FakeBackend : LlamaBackend {
        override val available = true
        val events: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
        val prompts = CopyOnWriteArrayList<String>()
        private val next = AtomicLong(100)
        val liveModels: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        val liveContexts: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        val loadModelCalls = AtomicInteger(); val newContextCalls = AtomicInteger()
        val stopCalls = AtomicInteger()
        val kvLeaks = AtomicInteger()          // generate() started with a non-empty KV cache
        val misuse = CopyOnWriteArrayList<String>() // use-after-free etc.
        val concurrent = AtomicInteger(); val maxConcurrent = AtomicInteger()
        @Volatile var kvTokens = 0
        val stopFlag = AtomicBoolean(false)
        @Volatile var failLoadModel = false
        @Volatile var failNewContext = false
        @Volatile var tokenCounter: (String) -> Int = { it.length / 4 + 1 }
        /** What generate() does; receives the prompt. May block until stopFlag is set. */
        @Volatile var behaviour: (String) -> String = { "ok" }
        val generateEntered = CountDownLatch(1)

        override fun loadModel(path: String, nGpuLayers: Int): Long {
            loadModelCalls.incrementAndGet(); events += "loadModel"
            if (failLoadModel) return 0
            return next.incrementAndGet().also { liveModels += it }
        }
        override fun freeModel(modelHandle: Long) {
            if (!liveModels.remove(modelHandle)) misuse += "freeModel on dead handle"
            events += "freeModel"
        }
        override fun newContext(modelHandle: Long, nCtx: Int, nThreads: Int): Long {
            newContextCalls.incrementAndGet(); events += "newContext"
            if (modelHandle !in liveModels) misuse += "newContext on dead model"
            if (failNewContext) return 0
            return next.incrementAndGet().also { liveContexts += it }
        }
        override fun freeContext(ctxHandle: Long) {
            if (concurrent.get() != 0) misuse += "freeContext while generating"
            if (!liveContexts.remove(ctxHandle)) misuse += "freeContext on dead handle"
            events += "freeContext"
        }
        override fun resetContext(ctxHandle: Long) {
            if (ctxHandle !in liveContexts) misuse += "resetContext on dead handle"
            kvTokens = 0; stopFlag.set(false); events += "reset"
        }
        override fun tokenCount(modelHandle: Long, text: String, addSpecial: Boolean): Int {
            if (modelHandle !in liveModels) misuse += "tokenCount on dead model"
            return tokenCounter(text)
        }
        override fun generate(ctxHandle: Long, prompt: String, maxTokens: Int, addSpecial: Boolean, stops: Array<String>): ByteArray {
            if (ctxHandle !in liveContexts) { misuse += "generate on dead handle"; throw IllegalStateException("context already freed") }
            val c = concurrent.incrementAndGet(); maxConcurrent.updateAndGet { maxOf(it, c) }
            events += "generate:start"; prompts += prompt
            if (kvTokens != 0) kvLeaks.incrementAndGet()
            kvTokens += tokenCounter(prompt)
            generateEntered.countDown()
            try { return behaviour(prompt).toByteArray(Charsets.UTF_8) }
            finally { concurrent.decrementAndGet(); events += "generate:end" }
        }
        override fun requestStop(ctxHandle: Long) {
            if (ctxHandle !in liveContexts) misuse += "requestStop on dead handle"
            stopCalls.incrementAndGet(); stopFlag.set(true)
        }

        /** Blocks like a long generation until stopped; returns a partial text. */
        fun blockUntilStopped(): (String) -> String = {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!stopFlag.get() && System.nanoTime() < deadline) Thread.sleep(2)
            if (stopFlag.get()) "partial" else "TIMEOUT"
        }
    }

    private val pool = Executors.newCachedThreadPool()
    private val io = pool.asCoroutineDispatcher()
    private val template = ChatMLTemplate()
    private val fake = FakeBackend()
    private val session = LlmSession(fake, io)

    @After fun tearDown() { pool.shutdownNow() }

    private fun user(text: String) = listOf(ChatTurn("system", "SYS"), ChatTurn("user", text))
    private fun load() = runBlocking { session.load("/fake/model.gguf", 4096, 4) }
    private fun text(r: ChatResult) = (r as ChatResult.Text).text

    // ---------------- loading ----------------

    @Test fun load_success_and_failure_paths() {
        assertNull(load()); assertTrue(session.isLoaded)

        val failModel = FakeBackend().apply { failLoadModel = true }
        val s1 = LlmSession(failModel, io)
        assertNotNull(runBlocking { s1.load("/x", 4096, 4) }); assertFalse(s1.isLoaded)

        val failCtx = FakeBackend().apply { failNewContext = true }
        val s2 = LlmSession(failCtx, io)
        assertNotNull(runBlocking { s2.load("/x", 4096, 4) }); assertFalse(s2.isLoaded)
        assertTrue("model must be freed when the context cannot be created", failCtx.liveModels.isEmpty())
        assertTrue(failCtx.misuse.isEmpty())
    }

    @Test fun loading_again_frees_the_previous_model_and_context() {
        load(); load()
        assertEquals(1, fake.liveModels.size); assertEquals(1, fake.liveContexts.size)
        assertEquals(2, fake.loadModelCalls.get())
        assertTrue(fake.misuse.isEmpty())
    }

    // ---------------- consecutive requests on ONE loaded model ----------------

    @Test fun consecutive_requests_each_start_with_a_clean_context() {
        load()
        fake.behaviour = { p -> "reply-" + fake.prompts.size }
        val results = (1..4).map { i -> runBlocking { session.chat(user("question $i"), template, 64) } }
        assertEquals(listOf("reply-1", "reply-2", "reply-3", "reply-4"), results.map { text(it) })

        assertEquals("model is loaded exactly once for all requests", 1, fake.loadModelCalls.get())
        assertEquals("no request started with leftover KV tokens", 0, fake.kvLeaks.get())
        // every generation is bracketed by a context reset before and after it
        val gen = fake.events.filter { it == "reset" || it.startsWith("generate") }
        val expected = (1..4).flatMap { listOf("reset", "generate:start", "generate:end", "reset") }
        assertEquals(expected, gen)
        assertEquals("nothing is left in the cache after the last request", 0, fake.kvTokens)
        assertTrue(fake.misuse.isEmpty())
    }

    @Test fun a_request_only_contains_its_own_conversation() {
        load()
        runBlocking { session.chat(user("SECRET-FIRST"), template, 64) }
        runBlocking { session.chat(user("second"), template, 64) }
        assertTrue(fake.prompts[0].contains("SECRET-FIRST"))
        assertFalse("text of request 1 must not appear in request 2", fake.prompts[1].contains("SECRET-FIRST"))
    }

    @Test fun explicit_history_is_sent_in_chat_template_format() {
        load()
        val msgs = listOf(ChatTurn("system", "SYS"), ChatTurn("user", "u1"), ChatTurn("assistant", "a1"), ChatTurn("user", "u2"))
        runBlocking { session.chat(msgs, template, 64) }
        assertEquals(template.format(msgs), fake.prompts.single())
    }

    @Test fun output_is_cleaned_with_the_templates_stop_markers() {
        load()
        fake.behaviour = { "سلام دنیا<|im_end|>\n<|im_start|>user\nhallucinated" }
        assertEquals("سلام دنیا", text(runBlocking { session.chat(user("hi"), template, 64) }))
    }

    // ---------------- request validation ----------------

    @Test fun chat_before_load_and_after_unload_fails_without_touching_the_backend() {
        val r = runBlocking { session.chat(user("hi"), template, 64) }
        assertEquals(FailureReason.NOT_LOADED, (r as ChatResult.Failure).reason)
        load(); runBlocking { session.unload() }
        val r2 = runBlocking { session.chat(user("hi"), template, 64) }
        assertEquals(FailureReason.NOT_LOADED, (r2 as ChatResult.Failure).reason)
        assertTrue(fake.prompts.isEmpty())
        assertTrue(fake.misuse.isEmpty())
    }

    @Test fun invalid_conversation_is_rejected_before_generation() {
        load()
        val r = runBlocking { session.chat(listOf(ChatTurn("assistant", "x")), template, 64) }
        assertEquals(FailureReason.INVALID_REQUEST, (r as ChatResult.Failure).reason)
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun old_history_is_dropped_to_fit_and_an_oversized_message_is_refused() {
        load() // context 4096, max 64 new tokens
        fake.tokenCounter = { it.length } // 1 token per char -> tiny window in practice
        val big = "z".repeat(1500)
        val msgs = listOf(ChatTurn("user", "old $big"), ChatTurn("assistant", "oldA $big"), ChatTurn("user", "old2 $big"),
            ChatTurn("assistant", "old2A $big"), ChatTurn("user", "now"))
        val ok = runBlocking { session.chat(msgs, template, 64) } as ChatResult.Text
        assertTrue(ok.droppedTurns > 0)
        assertFalse(fake.prompts.last().contains("old "))
        assertTrue(fake.prompts.last().contains("now"))

        val before = fake.prompts.size
        val tooLong = runBlocking { session.chat(listOf(ChatTurn("user", "y".repeat(9000))), template, 64) }
        assertEquals(FailureReason.PROMPT_TOO_LONG, (tooLong as ChatResult.Failure).reason)
        assertEquals("a refused request never reaches generate()", before, fake.prompts.size)
    }

    @Test fun native_failure_is_reported_and_the_context_is_still_reset() {
        load()
        fake.behaviour = { throw IllegalStateException("decode prompt failed") }
        val r = runBlocking { session.chat(user("hi"), template, 64) }
        assertEquals(FailureReason.NATIVE_ERROR, (r as ChatResult.Failure).reason)
        assertEquals("reset", fake.events.last())
        assertEquals(0, fake.kvTokens)
        // the session is still usable
        fake.behaviour = { "fine" }
        assertEquals("fine", text(runBlocking { session.chat(user("again"), template, 64) }))
    }

    // ---------------- stop ----------------

    @Test fun stop_ends_a_running_generation_and_returns_partial_text_then_session_keeps_working() {
        load()
        fake.behaviour = fake.blockUntilStopped()
        val job: Deferred<ChatResult> = runBlocking { kotlinx.coroutines.CoroutineScope(io).async { session.chat(user("long"), template, 512) } }
        assertTrue(fake.generateEntered.await(5, TimeUnit.SECONDS))
        session.stop()
        assertEquals("partial", text(runBlocking { withTimeout(5000) { job.await() } }))
        assertEquals(1, fake.stopCalls.get())

        fake.behaviour = { "next ok" }
        assertEquals("the stop request must not leak into the next request", "next ok", text(runBlocking { session.chat(user("q"), template, 64) }))
        assertEquals(0, fake.kvLeaks.get())
    }

    @Test fun stop_when_idle_never_reaches_the_native_side() {
        load()
        session.stop(); session.stop()
        runBlocking { session.chat(user("q"), template, 64) }
        session.stop()
        assertEquals(0, fake.stopCalls.get())
    }

    @Test fun cancelling_the_caller_stops_the_native_generation() {
        load()
        fake.behaviour = fake.blockUntilStopped()
        runBlocking {
            val job = kotlinx.coroutines.CoroutineScope(io).async { session.chat(user("long"), template, 512) }
            assertTrue(fake.generateEntered.await(5, TimeUnit.SECONDS))
            withTimeout(5000) { job.cancelAndJoin() }
        }
        assertEquals(1, fake.stopCalls.get())
        assertEquals("generation ended and was reset", 0, fake.concurrent.get())
        assertEquals(0, fake.kvTokens)
        fake.behaviour = { "after cancel" }
        assertEquals("after cancel", text(runBlocking { session.chat(user("q"), template, 64) }))
    }

    // ---------------- concurrency and release ----------------

    @Test fun concurrent_requests_are_serialised() {
        load()
        fake.behaviour = { Thread.sleep(15); "ok" }
        runBlocking {
            val jobs = (1..8).map { i -> kotlinx.coroutines.CoroutineScope(io).async { session.chat(user("q$i"), template, 64) } }
            jobs.forEach { assertEquals("ok", text(withTimeout(10_000) { it.await() })) }
        }
        assertEquals("two generations must never overlap", 1, fake.maxConcurrent.get())
        assertEquals(0, fake.kvLeaks.get())
        assertEquals(8, fake.prompts.size)
    }

    @Test fun unload_during_generation_waits_for_it_and_never_frees_a_live_context() {
        load()
        fake.behaviour = fake.blockUntilStopped()
        runBlocking {
            val gen = kotlinx.coroutines.CoroutineScope(io).async { session.chat(user("long"), template, 512) }
            assertTrue(fake.generateEntered.await(5, TimeUnit.SECONDS))
            val unloading = kotlinx.coroutines.CoroutineScope(io).async { session.unload() }
            withTimeout(5000) { unloading.await() }
            assertEquals("partial", text(withTimeout(5000) { gen.await() }))
        }
        val e = fake.events
        assertTrue("free happens after the generation ended", e.indexOf("freeContext") > e.indexOf("generate:end"))
        assertFalse(session.isLoaded)
        assertTrue(fake.liveModels.isEmpty() && fake.liveContexts.isEmpty())
        assertTrue("no use-after-free: ${fake.misuse}", fake.misuse.isEmpty())
    }

    @Test fun requests_queued_behind_an_unload_do_not_run() {
        load()
        fake.behaviour = fake.blockUntilStopped()
        val results = runBlocking {
            val first = kotlinx.coroutines.CoroutineScope(io).async { session.chat(user("first"), template, 512) }
            assertTrue(fake.generateEntered.await(5, TimeUnit.SECONDS))
            val queued = kotlinx.coroutines.CoroutineScope(io).async { session.chat(user("queued"), template, 512) }
            delay(50) // let `queued` park on the mutex
            val unloading = kotlinx.coroutines.CoroutineScope(io).async { session.unload() }
            withTimeout(8000) { unloading.await() }
            listOf(withTimeout(8000) { first.await() }, withTimeout(8000) { queued.await() })
        }
        assertEquals("partial", text(results[0]))
        assertEquals(FailureReason.NOT_LOADED, (results[1] as ChatResult.Failure).reason)
        assertEquals("only the first request ever reached generate()", 1, fake.prompts.size)
        assertTrue(fake.misuse.isEmpty())
    }

    @Test fun double_unload_is_harmless() {
        load()
        runBlocking { session.unload(); session.unload() }
        assertTrue(fake.misuse.isEmpty())
        assertEquals(1, fake.events.count { it == "freeContext" })
    }
}
