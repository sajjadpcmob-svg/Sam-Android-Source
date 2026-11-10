package ir.elviran.sam.core

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Explicit production rules supplied for the Taffy line. All quantities are batch counts;
 * Friday is a non-production day and is retained in plans so date continuity is visible.
 */
data class TaffyDayCapacity(
    val date: LocalDate,
    val startTime: String?,
    val totalBatches: Int,
    val netBatches: Int,
    val lossBatches: Int,
    val cleaningWindow: String? = null
) {
    val isProductionDay: Boolean get() = totalBatches > 0
}

object TaffyCapacityRules {
    const val FILLED_BATCH_KG = 156.5

    fun forDate(date: LocalDate): TaffyDayCapacity = when (date.dayOfWeek) {
        DayOfWeek.FRIDAY -> TaffyDayCapacity(date, null, 0, 0, 0)
        DayOfWeek.THURSDAY -> TaffyDayCapacity(date, "07:00", 54, 52, 2, "24:30 تا جمعه 07:00")
        DayOfWeek.SATURDAY -> TaffyDayCapacity(date, "07:30", 75, 72, 3)
        else -> TaffyDayCapacity(date, "07:00", 75, 72, 3)
    }

    fun plan(from: LocalDate, calendarDays: Int): List<TaffyDayCapacity> {
        require(calendarDays in 1..366) { "بازه برنامه‌ریزی باید بین ۱ تا ۳۶۶ روز باشد" }
        return (0 until calendarDays).map { forDate(from.plusDays(it.toLong())) }
    }

    fun netFilledProductKg(day: TaffyDayCapacity): Double = day.netBatches * FILLED_BATCH_KG
}

data class KavandishPackagingPlan(
    val flavor: String,
    val totalKg: Double,
    val twoEndRatio: Double = 0.70
) {
    init {
        require(flavor.isNotBlank())
        require(totalKg.isFinite() && totalKg >= 0.0)
        require(twoEndRatio.isFinite() && twoEndRatio in 0.0..1.0)
    }
    val twoEndKg: Double get() = totalKg * twoEndRatio
    val cushionKg: Double get() = totalKg - twoEndKg
}

/** Keeps bitter and fruit flavors independent while splitting each flavor in parallel packs. */
object KavandishPackagingRules {
    fun plan(flavorTotalsKg: Map<String, Double>, twoEndRatio: Double = 0.70): List<KavandishPackagingPlan> {
        require(twoEndRatio.isFinite() && twoEndRatio in 0.0..1.0)
        return flavorTotalsKg.toSortedMap().map { (flavor, kg) ->
            KavandishPackagingPlan(flavor, kg, twoEndRatio)
        }
    }
}
