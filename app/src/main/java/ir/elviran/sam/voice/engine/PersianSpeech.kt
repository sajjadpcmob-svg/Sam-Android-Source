package ir.elviran.sam.voice.engine

/**
 * Pure, Android-free text normalisation for natural Persian TTS: converts digit strings and simple
 * Jalali dates into spoken Persian words so the synthesiser reads ۱۴۰۵/۰۹/۱۵ and 1250000 the way a
 * person would. Kept pure for unit testing.
 */
object PersianSpeech {
    private val months = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )
    private val ones = arrayOf("", "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه")
    private val teens = arrayOf("ده", "یازده", "دوازده", "سیزده", "چهارده", "پانزده", "شانزده", "هفده", "هجده", "نوزده")
    private val tens = arrayOf("", "", "بیست", "سی", "چهل", "پنجاه", "شصت", "هفتاد", "هشتاد", "نود")
    private val hundreds = arrayOf("", "صد", "دویست", "سیصد", "چهارصد", "پانصد", "ششصد", "هفتصد", "هشتصد", "نهصد")
    private val scales = listOf(1_000_000_000L to "میلیارد", 1_000_000L to "میلیون", 1_000L to "هزار")

    fun toEnglishDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) sb.append(when (c) {
            in '۰'..'۹' -> '0' + (c - '۰'); in '٠'..'٩' -> '0' + (c - '٠'); else -> c
        })
        return sb.toString()
    }

    private fun under1000(n: Int): String {
        val parts = mutableListOf<String>()
        val h = n / 100; val rem = n % 100
        if (h > 0) parts += hundreds[h]
        when {
            rem in 10..19 -> parts += teens[rem - 10]
            else -> { val t = rem / 10; val o = rem % 10; if (t > 0) parts += tens[t]; if (o > 0) parts += ones[o] }
        }
        return parts.joinToString(" و ")
    }

    fun numberToWords(value: Long): String {
        if (value == 0L) return "صفر"
        var n = value; val neg = n < 0; if (neg) n = -n
        val parts = mutableListOf<String>()
        for ((scale, name) in scales) {
            if (n >= scale) { val q = (n / scale).toInt(); parts += under1000(q) + " " + name; n %= scale }
        }
        if (n > 0) parts += under1000(n.toInt())
        val words = parts.joinToString(" و ")
        return if (neg) "منفی $words" else words
    }

    /** Replace standalone integer tokens in a sentence with their spoken form. */
    fun speakNumbersIn(text: String): String {
        val latin = toEnglishDigits(text)
        return Regex("\\d{1,15}").replace(latin) { m -> numberToWords(m.value.toLong()) }
    }

    /** Jalali date yyyy/mm/dd (Persian or Latin digits) -> «۱۵ آذر ۱۴۰۵» spoken words. */
    fun jalaliDateToWords(raw: String): String? {
        val m = Regex("(\\d{3,4})[/\u002d.](\\d{1,2})[/\u002d.](\\d{1,2})").find(toEnglishDigits(raw)) ?: return null
        val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
        if (mo !in 1..12 || d !in 1..31) return null
        return "${numberToWords(d.toLong())} ${months[mo - 1]} ${numberToWords(y.toLong())}"
    }

    /** Full normalisation pipeline used before handing text to the synthesiser. */
    fun normalizeForSpeech(text: String): String {
        var s = toEnglishDigits(text)
        s = Regex("(\\d{3,4})[/\u002d.](\\d{1,2})[/\u002d.](\\d{1,2})").replace(s) { mr ->
            jalaliDateToWords(mr.value) ?: mr.value
        }
        s = Regex("\\d{1,15}").replace(s) { m -> numberToWords(m.value.toLong()) }
        return s
    }
}
