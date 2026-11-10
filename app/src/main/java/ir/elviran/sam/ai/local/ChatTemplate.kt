package ir.elviran.sam.ai.local

import ir.elviran.sam.ai.ChatTurn
import java.util.concurrent.ConcurrentHashMap

/** Role names understood by every [ChatTemplate]. */
object ChatRole {
    const val SYSTEM = "system"
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

/** Stable ids that [LlmModelSpec.templateId] refers to. */
object ChatTemplateIds {
    const val CHATML_QWEN25 = "chatml-qwen25"
    const val GEMMA2 = "gemma2"
    /** Default for a spec that did not declare a template: the engine refuses to load it. */
    const val UNSPECIFIED = "unspecified"
}

/**
 * Turns a conversation (system prompt, history, new user message) into the exact text a given model
 * family was trained on, and knows how that family ends a turn. One implementation per model family;
 * add a new family by implementing this interface and calling [ChatTemplateRegistry.register].
 */
interface ChatTemplate {
    val id: String

    /**
     * Literal strings that end the assistant turn. Generation stops at the first occurrence and the
     * text from that point on is discarded (defence in depth on top of the model's own EOG tokens).
     */
    val stopStrings: List<String>

    /** True when the tokenizer must add BOS itself because [format] does not emit it. */
    val addSpecialTokens: Boolean

    /** Literal control tokens owned by this template; stripped from user-supplied text. */
    val reservedTokens: List<String>

    /**
     * Full prompt for one request, ending with the open assistant turn.
     * @throws IllegalArgumentException when there is no user message or the last message is not a user message.
     */
    fun format(messages: List<ChatTurn>): String

    /** Strips stop markers and anything after them, plus an incomplete trailing UTF-8 character. */
    fun cleanOutput(raw: String): String = ChatTemplateSupport.cleanOutput(raw, stopStrings)
}

internal object ChatTemplateSupport {
    private val knownRoles = setOf(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT)

    /**
     * Removes every reserved control token from [text]. Loops until stable so that a token cannot be
     * re-assembled from the pieces left behind by a first removal (e.g. `<|im_<|im_end|>end|>`).
     * Needed because the native tokenizer parses special tokens: without this a user could forge turns.
     */
    fun sanitize(text: String, reserved: List<String>): String {
        var current = text
        while (true) {
            var next = current
            for (token in reserved) next = next.replace(token, "")
            if (next == current) return current
            current = next
        }
    }

    /** Keeps known roles, drops blank non-user messages, sanitises content, validates the ending. */
    fun prepare(messages: List<ChatTurn>, reserved: List<String>): List<ChatTurn> {
        val cleaned = messages
            .filter { it.role in knownRoles }
            .map { ChatTurn(it.role, sanitize(it.content, reserved)) }
            .filter { it.role == ChatRole.USER || it.content.isNotBlank() }
        require(cleaned.isNotEmpty() && cleaned.last().role == ChatRole.USER) { "آخرین پیام باید از کاربر باشد" }
        require(cleaned.last().content.isNotBlank()) { "پیام کاربر خالی است" }
        return cleaned
    }

    fun cleanOutput(raw: String, stops: List<String>): String {
        var end = raw.length
        for (s in stops) {
            val i = raw.indexOf(s)
            if (i in 0 until end) end = i
        }
        return raw.substring(0, end).trimEnd('\uFFFD').trim()
    }
}

/**
 * ChatML, as used by Qwen2.5-Instruct:
 *
 *     <|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n
 *
 * Like the official Qwen2.5 template, a default system message is inserted when none is given.
 * BOS is not used by this family, so the tokenizer adds nothing ([addSpecialTokens] = false).
 */
class ChatMLTemplate(
    override val id: String = ChatTemplateIds.CHATML_QWEN25,
    private val defaultSystem: String = QWEN_DEFAULT_SYSTEM
) : ChatTemplate {
    override val stopStrings = listOf("<|im_end|>", "<|endoftext|>", "<|im_start|>")
    override val addSpecialTokens = false
    override val reservedTokens = listOf("<|im_start|>", "<|im_end|>", "<|endoftext|>")

    override fun format(messages: List<ChatTurn>): String {
        val msgs = ChatTemplateSupport.prepare(messages, reservedTokens)
        val sb = StringBuilder()
        if (msgs.first().role != ChatRole.SYSTEM) turn(sb, ChatRole.SYSTEM, defaultSystem)
        msgs.forEach { turn(sb, it.role, it.content) }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun turn(sb: StringBuilder, role: String, content: String) {
        sb.append("<|im_start|>").append(role).append('\n').append(content).append("<|im_end|>\n")
    }

    companion object {
        const val QWEN_DEFAULT_SYSTEM = "You are Qwen, created by Alibaba Cloud. You are a helpful assistant."
    }
}

/**
 * Gemma 2 instruction format. Gemma has no system role, so system text is prepended to the first user
 * turn; the assistant role is called `model`; consecutive same-role messages are merged and the
 * conversation always starts with a user turn. BOS is added by the tokenizer ([addSpecialTokens] = true).
 */
class Gemma2Template(override val id: String = ChatTemplateIds.GEMMA2) : ChatTemplate {
    override val stopStrings = listOf("<end_of_turn>", "<eos>", "<start_of_turn>")
    override val addSpecialTokens = true
    override val reservedTokens = listOf("<start_of_turn>", "<end_of_turn>", "<bos>", "<eos>")

    override fun format(messages: List<ChatTurn>): String {
        val msgs = ChatTemplateSupport.prepare(messages, reservedTokens)
        val system = msgs.filter { it.role == ChatRole.SYSTEM }.joinToString("\n\n") { it.content.trim() }
        // Merge consecutive same-role turns, then drop any leading assistant turns.
        val turns = ArrayList<ChatTurn>()
        for (m in msgs) {
            if (m.role == ChatRole.SYSTEM) continue
            val last = turns.lastOrNull()
            if (last != null && last.role == m.role) turns[turns.lastIndex] = ChatTurn(m.role, last.content.trim() + "\n\n" + m.content.trim())
            else turns.add(ChatTurn(m.role, m.content.trim()))
        }
        while (turns.isNotEmpty() && turns.first().role != ChatRole.USER) turns.removeAt(0)
        require(turns.isNotEmpty()) { "پیام کاربر یافت نشد" }
        if (system.isNotEmpty()) turns[0] = ChatTurn(ChatRole.USER, system + "\n\n" + turns[0].content)
        val sb = StringBuilder()
        turns.forEach { t ->
            sb.append("<start_of_turn>").append(if (t.role == ChatRole.ASSISTANT) "model" else "user").append('\n')
                .append(t.content).append("<end_of_turn>\n")
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }
}

/** Extension point: register a [ChatTemplate] under its id and reference that id from a [LlmModelSpec]. */
object ChatTemplateRegistry {
    private val templates = ConcurrentHashMap<String, ChatTemplate>()

    init {
        register(ChatMLTemplate())
        register(Gemma2Template())
    }

    fun register(template: ChatTemplate) { templates[template.id] = template }
    fun unregister(id: String) { templates.remove(id) }
    fun get(id: String?): ChatTemplate? = id?.let { templates[it] }
    fun forSpec(spec: LlmModelSpec): ChatTemplate? = get(spec.templateId)
    fun registeredIds(): Set<String> = templates.keys.toSet()
}

/**
 * Keeps a conversation inside the model's context window. The system prompt(s) and the newest user
 * message are never dropped; the oldest history turns go first (always leaving history that starts with
 * a user turn). [countTokens] must count the *formatted* text the way the native tokenizer will.
 */
object PromptFitter {
    sealed interface Result {
        data class Fitted(val prompt: String, val droppedTurns: Int, val tokens: Int) : Result
        data class TooLong(val tokens: Int, val budget: Int) : Result
    }

    fun fit(template: ChatTemplate, messages: List<ChatTurn>, budgetTokens: Int, countTokens: (String) -> Int): Result {
        val msgs = ChatTemplateSupport.prepare(messages, template.reservedTokens)
        val system = msgs.filter { it.role == ChatRole.SYSTEM }
        val last = msgs.last()
        val history = msgs.dropLast(1).filter { it.role != ChatRole.SYSTEM }.toMutableList()
        var dropped = 0
        var lastTokens = 0
        while (true) {
            val prompt = template.format(system + history + last)
            lastTokens = countTokens(prompt)
            if (lastTokens <= budgetTokens) return Result.Fitted(prompt, dropped, lastTokens)
            if (history.isEmpty()) return Result.TooLong(lastTokens, budgetTokens)
            history.removeAt(0); dropped++
            while (history.isNotEmpty() && history.first().role != ChatRole.USER) { history.removeAt(0); dropped++ }
        }
    }
}
