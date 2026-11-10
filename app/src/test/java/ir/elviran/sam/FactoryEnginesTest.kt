package ir.elviran.sam

import ir.elviran.sam.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FactoryEnginesTest {

    @Test fun oee_computesComponentsAndClamps() {
        val r = OeeEngine.compute(OeeInput(plannedMinutes = 480.0, downtimeMinutes = 80.0, idealRatePerMin = 2.0, totalCount = 700.0, goodCount = 680.0))
        assertEquals(400.0, r.runMinutes, 1e-6)
        assertEquals(400.0 / 480.0, r.availability, 1e-6)
        assertEquals(700.0 / 800.0, r.performance, 1e-6)
        assertEquals(680.0 / 700.0, r.quality, 1e-6)
        // impossible over-production must never push OEE above 100%
        assertTrue(OeeEngine.compute(OeeInput(100.0, 0.0, 1.0, 999999.0, 999999.0)).oee <= 1.0)
    }

    @Test fun scheduler_respectsCapacityPriorityAndDueDates() {
        val lines = listOf(SchedLine(1, "\u06a9\u0627\u0648\u0646\u062f\u06cc\u0634", 100.0), SchedLine(2, "\u062a\u0627\u0641\u06cc", 50.0))
        val orders = listOf(
            SchedOrder(10, 1, "A", 250.0, dueDay = 2),
            SchedOrder(11, 1, "B", 100.0, dueDay = 5, priority = 1),
            SchedOrder(12, 2, "C", 120.0, dueDay = 1),
            SchedOrder(99, 7, "orphan", 10.0)
        )
        val res = ProductionScheduler.schedule(lines, orders)
        assertTrue(res.unassigned.any { it.id == 99L })
        val o10 = res.scheduled.first { it.order.id == 10L }
        assertEquals(3, o10.finishDay)
        assertFalse(o10.onTime)
        assertEquals(1, o10.daysLate)
    }
}
