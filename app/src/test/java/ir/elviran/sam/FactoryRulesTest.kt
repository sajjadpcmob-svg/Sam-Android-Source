package ir.elviran.sam

import ir.elviran.sam.core.KavandishPackagingRules
import ir.elviran.sam.core.TaffyCapacityRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FactoryRulesTest {
    @Test fun taffyCalendarHonorsFridayAndSaturdayStart() {
        // 2026-11-06 is Friday; 2026-11-07 Saturday; 2026-11-08 Sunday.
        val days = TaffyCapacityRules.plan(LocalDate.of(2026, 11, 6), 4)
        assertFalse(days[0].isProductionDay)
        assertEquals("07:30", days[1].startTime)
        assertEquals(75, days[1].totalBatches)
        assertEquals(72, days[1].netBatches)
        assertEquals("07:00", days[2].startTime)
        assertEquals(75, days[2].totalBatches)
    }

    @Test fun taffyThursdayHasReducedCapacityAndCleaningWindow() {
        // 2026-11-05 is Thursday.
        val day = TaffyCapacityRules.forDate(LocalDate.of(2026, 11, 5))
        assertEquals(54, day.totalBatches)
        assertEquals(52, day.netBatches)
        assertEquals(2, day.lossBatches)
        assertEquals("24:30 تا جمعه 07:00", day.cleaningWindow)
    }

    @Test fun filledBatchMassIsExact() {
        val day = TaffyCapacityRules.forDate(LocalDate.of(2026, 11, 8))
        assertEquals(11268.0, TaffyCapacityRules.netFilledProductKg(day), 0.0001)
    }

    @Test fun kavandishFlavorsStaySeparateAndPackagingSumsToTotal() {
        val plans = KavandishPackagingRules.plan(mapOf("تلخ" to 1000.0, "میوه" to 2000.0))
        assertEquals(2, plans.size)
        val bitter = plans.first { it.flavor == "تلخ" }
        assertEquals(700.0, bitter.twoEndKg, 0.0001)
        assertEquals(300.0, bitter.cushionKg, 0.0001)
        assertEquals(bitter.totalKg, bitter.twoEndKg + bitter.cushionKg, 0.0001)
    }
}
