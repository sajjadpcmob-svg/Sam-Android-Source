package ir.elviran.sam.core

/**
 * Pure-Kotlin Jalali (Solar Hijri) calendar. No Android/ICU dependency, fully unit-testable.
 *
 * Correctness focus (known bug sources in Persian apps):
 *  - Esfand has 30 days ONLY in a leap year; otherwise 29.
 *  - A monthly due-day of 30 that lands on a non-leap Esfand must clamp to 29,
 *    but stay 30 on a leap Esfand.
 *
 * Leap-year rule matches Iran's official (jalaali-js "breaks") calendar for the supported range
 * (Jalali 1178..1633 / roughly Gregorian 1800..2256). This is important: the simple 33-year
 * arithmetic cycle disagrees with the official calendar for 1403 (1403 IS leap, 1404/1405 are not).
 */
data class JalaliDate(val year: Int, val month: Int, val day: Int) {
    init {
        require(month in 1..12) { "ماه نامعتبر: $month" }
        require(day in 1..monthLength(year, month)) { "روز نامعتبر برای این ماه: $day" }
    }

    companion object {
        // ---- jalaali-js "breaks" leap-year engine (matches Iran's official calendar) ----
        private val BREAKS = intArrayOf(
            -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210,
            1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178
        )

        // integer division truncating toward zero + JS-style modulo (sign of dividend)
        private fun div(a: Int, b: Int): Int = a / b
        private fun mod(a: Int, b: Int): Int = a - (a / b) * b

        private data class JalCal(val leap: Int, val gy: Int, val march: Int)

        private fun jalCal(jy: Int): JalCal {
            val gy = jy + 621
            var leapJ = -14
            var jp = BREAKS[0]
            require(jy in jp until BREAKS[BREAKS.size - 1]) { "سال خارج از بازه پشتیبانی‌شده: $jy" }
            var jump = 0
            for (i in 1 until BREAKS.size) {
                val jm = BREAKS[i]
                jump = jm - jp
                if (jy < jm) break
                leapJ += div(jump, 33) * 8 + div(mod(jump, 33), 4)
                jp = jm
            }
            var n = jy - jp
            leapJ += div(n, 33) * 8 + div(mod(n, 33) + 3, 4)
            if (mod(jump, 33) == 4 && jump - n == 4) leapJ += 1
            val leapG = div(gy, 4) - div((div(gy, 100) + 1) * 3, 4) - 150
            val march = 20 + leapJ - leapG
            if (jump - n < 6) n = n - jump + div(jump + 4, 33) * 33
            var leap = mod(mod(n + 1, 33) - 1, 4)
            if (leap == -1) leap = 4
            return JalCal(leap, gy, march)
        }

        fun isLeapYear(year: Int): Boolean = jalCal(year).leap == 0

        fun monthLength(year: Int, month: Int): Int = when (month) {
            in 1..6 -> 31
            in 7..11 -> 30
            12 -> if (isLeapYear(year)) 30 else 29
            else -> throw IllegalArgumentException("ماه نامعتبر: $month")
        }

        // ---- Gregorian <-> Jalali via day-number (jalaali-js g2d/d2g/j2d/d2j) ----
        private fun g2d(gy: Int, gm: Int, gd: Int): Int {
            var d = div((gy + div(gm - 8, 6) + 100100) * 1461, 4) +
                div(153 * mod(gm + 9, 12) + 2, 5) + gd - 34840408
            d = d - div(div(gy + 100100 + div(gm - 8, 6), 100) * 3, 4) + 752
            return d
        }

        private fun d2g(jdn: Int): Triple<Int, Int, Int> {
            var j = 4 * jdn + 139361631
            j += div(div(4 * jdn + 183187720, 146097) * 3, 4) * 4 - 3908
            val i = div(mod(j, 1461), 4) * 5 + 308
            val gd = div(mod(i, 153), 5) + 1
            val gm = mod(div(i, 153), 12) + 1
            val gy = div(j, 1461) - 100100 + div(8 - gm, 6)
            return Triple(gy, gm, gd)
        }

        private fun j2d(jy: Int, jm: Int, jd: Int): Int {
            val r = jalCal(jy)
            return g2d(r.gy, 3, r.march) + (jm - 1) * 31 - div(jm, 7) * (jm - 7) + jd - 1
        }

        private fun d2j(jdn: Int): JalaliDate {
            val gy = d2g(jdn).first
            var jy = gy - 621
            val r = jalCal(jy)
            val jdn1f = g2d(gy, 3, r.march)
            var k = jdn - jdn1f
            if (k >= 0) {
                if (k <= 185) return JalaliDate(jy, 1 + div(k, 31), mod(k, 31) + 1)
                k -= 186
            } else {
                jy -= 1
                k += 179
                if (r.leap == 1) k += 1
            }
            return JalaliDate(jy, 7 + div(k, 30), mod(k, 30) + 1)
        }

        private val MONTH_NAMES = listOf(
            "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
        )

        fun monthName(month: Int): String = MONTH_NAMES[month - 1]

        fun fromGregorian(gy: Int, gm: Int, gd: Int): JalaliDate = d2j(g2d(gy, gm, gd))

        /** Converts a Jalali Y/M/D to a Gregorian (year, month, day) triple. */
        fun toGregorian(j: JalaliDate): Triple<Int, Int, Int> = d2g(j2d(j.year, j.month, j.day))

        private const val FA_DIGITS = "۰۱۲۳۴۵۶۷۸۹"
        fun toPersianDigits(input: String): String = buildString {
            for (ch in input) append(if (ch in '0'..'9') FA_DIGITS[ch - '0'] else ch)
        }
        fun toEnglishDigits(input: String): String = buildString {
            for (ch in input) {
                val idx = FA_DIGITS.indexOf(ch)
                append(if (idx >= 0) ('0' + idx) else ch)
            }
        }
    }

    /** Advance this date by whole months keeping the day, clamping to the target month length. */
    fun plusMonthsClampDay(months: Int): JalaliDate {
        val total = (year * 12 + (month - 1)) + months
        val ny = total / 12
        val nm = total % 12 + 1
        val nd = day.coerceAtMost(monthLength(ny, nm))
        return JalaliDate(ny, nm, nd)
    }

    fun format(persianDigits: Boolean = true): String {
        val raw = "%04d/%02d/%02d".format(year, month, day)
        return if (persianDigits) toPersianDigits(raw) else raw
    }

    fun formatLong(persianDigits: Boolean = true): String {
        val raw = "$day ${monthName(month)} $year"
        return if (persianDigits) toPersianDigits(raw) else raw
    }

    fun isValid(): Boolean = runCatching { JalaliDate(year, month, day) }.isSuccess
}
