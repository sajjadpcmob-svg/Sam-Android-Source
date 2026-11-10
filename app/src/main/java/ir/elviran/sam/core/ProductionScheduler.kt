package ir.elviran.sam.core

/**
 * Pure-logic production scheduler. Given lines (with a daily capacity) and orders (quantity + due
 * day offset), it packs orders onto their line day-by-day respecting capacity, and reports the
 * finish day plus whether each order can meet its due day. No Android dependency — unit-testable.
 *
 * Day numbers are integer offsets from "today" (0 = today). Capacity unit must match order quantity
 * unit (the caller keeps units consistent, e.g. kg or cartons).
 */
data class SchedLine(val id: Long, val name: String, val capacityPerDay: Double)

data class SchedOrder(
    val id: Long,
    val lineId: Long,
    val product: String,
    val quantity: Double,
    val dueDay: Int? = null,      // null = no deadline
    val priority: Int = 0         // higher runs first
)

data class ScheduledOrder(
    val order: SchedOrder,
    val startDay: Int,
    val finishDay: Int,
    val onTime: Boolean,
    val daysLate: Int
)

data class ScheduleResult(
    val scheduled: List<ScheduledOrder>,
    val unassigned: List<SchedOrder>   // orders whose line is missing or has zero capacity
) {
    val lateOrders get() = scheduled.filter { !it.onTime }
    val allOnTime get() = unassigned.isEmpty() && lateOrders.isEmpty()
}

object ProductionScheduler {

    fun schedule(lines: List<SchedLine>, orders: List<SchedOrder>): ScheduleResult {
        val lineById = lines.associateBy { it.id }
        // next free day per line, and remaining capacity on that day
        val cursorDay = HashMap<Long, Int>()
        val remainingToday = HashMap<Long, Double>()

        val scheduled = ArrayList<ScheduledOrder>()
        val unassigned = ArrayList<SchedOrder>()

        // Within a line: priority desc, then earliest due, then smallest quantity (SPT-ish).
        val ordered = orders.sortedWith(
            compareByDescending<SchedOrder> { it.priority }
                .thenBy { it.dueDay ?: Int.MAX_VALUE }
                .thenBy { it.quantity }
        )

        for (o in ordered) {
            val line = lineById[o.lineId]
            if (line == null || line.capacityPerDay <= 0.0 || o.quantity <= 0.0) {
                unassigned += o
                continue
            }
            var day = cursorDay.getOrPut(o.lineId) { 0 }
            var free = remainingToday.getOrPut(o.lineId) { line.capacityPerDay }
            var remaining = o.quantity
            val startDay = day
            while (remaining > 1e-9) {
                if (free <= 1e-9) { day += 1; free = line.capacityPerDay }
                val take = minOf(free, remaining)
                remaining -= take
                free -= take
            }
            cursorDay[o.lineId] = day
            remainingToday[o.lineId] = free
            val finishDay = day
            val onTime = o.dueDay == null || finishDay <= o.dueDay
            val daysLate = if (o.dueDay == null) 0 else (finishDay - o.dueDay).coerceAtLeast(0)
            scheduled += ScheduledOrder(o, startDay, finishDay, onTime, daysLate)
        }
        return ScheduleResult(scheduled.sortedBy { it.finishDay }, unassigned)
    }
}
