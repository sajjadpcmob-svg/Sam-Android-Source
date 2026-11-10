package ir.elviran.sam.core

/**
 * Pure-logic OEE (Overall Equipment Effectiveness) calculator for a production line / machine.
 * No Android dependency — fully unit-testable.
 *
 *   Availability = runTime / plannedTime
 *   Performance  = (idealRatePerMin * runTimeMinutes) is not used directly; instead we use
 *                  totalCount / theoreticalMaxCount, where theoreticalMaxCount = idealRatePerMin * runTime.
 *   Quality      = goodCount / totalCount
 *   OEE          = Availability * Performance * Quality
 *
 * All inputs are validated; impossible ratios are clamped to [0,1] so a data-entry slip can never
 * produce an OEE above 100%.
 */
data class OeeInput(
    val plannedMinutes: Double,     // planned production time (shift minus planned stops)
    val downtimeMinutes: Double,    // unplanned stops
    val idealRatePerMin: Double,    // ideal units per minute at full speed
    val totalCount: Double,         // units produced (good + scrap)
    val goodCount: Double           // good units
)

data class OeeResult(
    val availability: Double,
    val performance: Double,
    val quality: Double,
    val oee: Double,
    val runMinutes: Double
) {
    val availabilityPct get() = availability * 100
    val performancePct get() = performance * 100
    val qualityPct get() = quality * 100
    val oeePct get() = oee * 100
}

object OeeEngine {

    fun compute(input: OeeInput): OeeResult {
        require(input.plannedMinutes >= 0 && input.downtimeMinutes >= 0) { "زمان نمی‌تواند منفی باشد" }
        require(input.idealRatePerMin >= 0 && input.totalCount >= 0 && input.goodCount >= 0) { "مقدار نمی‌تواند منفی باشد" }
        require(input.goodCount <= input.totalCount) { "تعداد سالم نمی‌تواند بیشتر از کل باشد" }

        val runMinutes = (input.plannedMinutes - input.downtimeMinutes).coerceAtLeast(0.0)
        val availability = ratio(runMinutes, input.plannedMinutes)
        val theoreticalMax = input.idealRatePerMin * runMinutes
        val performance = ratio(input.totalCount, theoreticalMax)
        val quality = ratio(input.goodCount, input.totalCount)
        val oee = availability * performance * quality
        return OeeResult(availability, performance, quality, oee, runMinutes)
    }

    /** 0 when denominator is 0; clamped to [0,1]. */
    private fun ratio(num: Double, den: Double): Double =
        if (den <= 0.0) 0.0 else (num / den).coerceIn(0.0, 1.0)

    /** World-class OEE is ~85%; this gives a simple Persian grade for the UI. */
    fun grade(oee: Double): String = when {
        oee >= 0.85 -> "عالی (جهانی)"
        oee >= 0.70 -> "خوب"
        oee >= 0.60 -> "قابل قبول"
        oee >= 0.40 -> "ضعیف"
        else -> "بحرانی"
    }
}
