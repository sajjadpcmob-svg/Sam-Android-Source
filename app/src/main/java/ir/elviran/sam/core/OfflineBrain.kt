package ir.elviran.sam.core

/**
 * Deterministic, fully-offline Persian command parser. This is NOT a generative language model:
 * it maps a spoken/typed Persian sentence to a known intent and extracts simple parameters with
 * rules, so basic factory and personal commands work with no network and no bundled model.
 *
 * Anything it does not recognise returns [Intent.Unknown] so the UI can fall back to the online
 * assistant (if configured) or tell the user plainly that it did not understand.
 */
sealed interface Intent {
    data class AddTask(val title: String) : Intent
    data class AddNote(val text: String) : Intent
    data class AddMoney(val amount: Long, val isIncome: Boolean, val title: String) : Intent
    data class Calculate(val expression: String, val result: Double) : Intent
    data class Query(val topic: String) : Intent          // e.g. "تولید امروز", "کارها"
    data object Unknown : Intent
}

object OfflineBrain {

    private val MONEY_WORDS = Regex("(درآمد|دریافت|فروش|خرج|هزینه|پرداخت|خرید)")
    private val INCOME_WORDS = Regex("(درآمد|دریافت|فروش)")
    private val NUM = Regex("[0-9۰-۹]+(?:[.,][0-9۰-۹]+)?")

    fun interpret(rawInput: String): Intent {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return Intent.Unknown
        val s = JalaliDate.toEnglishDigits(raw)

        // --- calculation: "حساب کن ..." or a pure arithmetic string ---
        if (s.startsWith("حساب کن") || s.startsWith("محاسبه") || isArithmetic(s)) {
            val expr = s.removePrefix("حساب کن").removePrefix("محاسبه").trim()
            val value = runCatching { SafeMathBridge.evaluate(expr) }.getOrNull()
            if (value != null) return Intent.Calculate(expr, value)
        }

        // --- money: income/expense with an amount ---
        if (MONEY_WORDS.containsMatchIn(s)) {
            val amount = NUM.find(s)?.value?.replace(",", "")?.toLongOrNull()
            if (amount != null) {
                val income = INCOME_WORDS.containsMatchIn(s)
                val title = s.replace(NUM, "").replace(MONEY_WORDS, "").trim().ifBlank { if (income) "درآمد" else "هزینه" }
                return Intent.AddMoney(amount, income, title)
            }
        }

        // --- task ---
        for (p in listOf("یادآوری کن", "یادم بنداز", "کار جدید", "اضافه کن کار", "تسک جدید")) {
            if (s.contains(p)) {
                val title = s.substringAfter(p).trim().trimStart('،', ':', 'ر').trim()
                if (title.isNotEmpty()) return Intent.AddTask(title)
            }
        }

        // --- note ---
        for (p in listOf("یادداشت کن", "یادداشت جدید", "نوت کن", "بنویس")) {
            if (s.contains(p)) {
                val text = s.substringAfter(p).trim().trimStart('،', ':').trim()
                if (text.isNotEmpty()) return Intent.AddNote(text)
            }
        }

        // --- queries ---
        if (s.contains("تولید") && (s.contains("امروز") || s.contains("چقدر") || s.contains("چند"))) return Intent.Query("تولید")
        if (s.contains("کارها") || s.contains("لیست کار")) return Intent.Query("کارها")
        if (s.contains("خلاصه مالی") || s.contains("موجودی") || s.contains("حساب")) return Intent.Query("مالی")

        return Intent.Unknown
    }

    private fun isArithmetic(s: String): Boolean {
        val t = s.replace(" ", "")
        return t.isNotEmpty() && t.all { it.isDigit() || it in "+-*/().×÷" } && t.any { it in "+-*/×÷" }
    }
}

/**
 * Indirection so the pure-logic brain can be unit-tested against a local evaluator while the app
 * binds it to the real bounded [ir.elviran.sam.SafeMath] parser at startup.
 */
object SafeMathBridge {
    @Volatile private var impl: ((String) -> Double)? = null
    fun bind(evaluator: (String) -> Double) { impl = evaluator }
    fun evaluate(expr: String): Double {
        val f = impl ?: throw IllegalStateException("ماشین‌حساب هنوز متصل نشده است")
        return f(expr.replace('×', '*').replace('÷', '/'))
    }
}
