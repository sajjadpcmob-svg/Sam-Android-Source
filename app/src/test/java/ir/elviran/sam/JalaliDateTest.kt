package ir.elviran.sam

import ir.elviran.sam.core.JalaliDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the official-calendar leap rule and the Esfand / monthly-due-day edge cases.
 *
 * Historical note: the naive 33-year arithmetic cycle wrongly reports 1403 as non-leap and 1404
 * as leap. Iran's official calendar (and the jalaali-js "breaks" engine we use) says 1403 IS leap
 * (30 Esfand 1403 == 20 March 2025) while 1404 and 1405 are NOT.
 */
class JalaliDateTest {

    @Test fun leapYears_matchOfficialCalendar() {
        assertTrue("1403 must be leap", JalaliDate.isLeapYear(1403))
        assertFalse("1404 must be non-leap", JalaliDate.isLeapYear(1404))
        assertFalse("1405 must be non-leap", JalaliDate.isLeapYear(1405))
        assertTrue(JalaliDate.isLeapYear(1399))
        assertTrue(JalaliDate.isLeapYear(1408))
    }

    @Test fun esfandLength_followsLeapRule() {
        assertEquals(30, JalaliDate.monthLength(1403, 12))
        assertEquals(29, JalaliDate.monthLength(1404, 12))
        assertEquals(29, JalaliDate.monthLength(1405, 12))
        assertEquals(31, JalaliDate.monthLength(1404, 1))
        assertEquals(30, JalaliDate.monthLength(1404, 7))
    }

    @Test fun esfand30_onlyValidInLeapYear() {
        // valid in 1403 (leap)
        JalaliDate(1403, 12, 30)
        // invalid in 1404 / 1405 (non-leap)
        assertThrows(IllegalArgumentException::class.java) { JalaliDate(1404, 12, 30) }
        assertThrows(IllegalArgumentException::class.java) { JalaliDate(1405, 12, 30) }
    }

    @Test fun monthlyDueDay30_clampsInNonLeapEsfand() {
        // Bahman 30, 1404 -> Esfand 1404 (non-leap) must clamp to 29
        val next = JalaliDate(1404, 11, 30).plusMonthsClampDay(1)
        assertEquals(12, next.month)
        assertEquals(29, next.day)
    }

    @Test fun monthlyDueDay30_staysInLeapEsfand() {
        // Bahman 30, 1403 -> Esfand 1403 (leap) must stay 30
        val next = JalaliDate(1403, 11, 30).plusMonthsClampDay(1)
        assertEquals(12, next.month)
        assertEquals(30, next.day)
    }

    @Test fun plusMonths_rollsOverYear() {
        val next = JalaliDate(1404, 12, 15).plusMonthsClampDay(1) // -> Farvardin next year
        assertEquals(1405, next.year)
        assertEquals(1, next.month)
        assertEquals(15, next.day)
    }

    @Test fun gregorianRoundTrip() {
        assertEquals(Triple(2024, 3, 20), JalaliDate.toGregorian(JalaliDate(1403, 1, 1)))
        assertEquals(JalaliDate(1403, 1, 1), JalaliDate.fromGregorian(2024, 3, 20))
        // 30 Esfand 1403 is the famous leap day == 20 March 2025
        assertEquals(Triple(2025, 3, 20), JalaliDate.toGregorian(JalaliDate(1403, 12, 30)))
        assertEquals(JalaliDate(1403, 12, 30), JalaliDate.fromGregorian(2025, 3, 20))
    }

    @Test fun digitConversion_isReversible() {
        assertEquals("\u06f1\u06f4\u06f0\u06f4", JalaliDate.toPersianDigits("1404"))
        assertEquals("1404", JalaliDate.toEnglishDigits("\u06f1\u06f4\u06f0\u06f4"))
    }
}
