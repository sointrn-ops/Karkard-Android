package ir.example.karkard

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Calculation rules for KARBAN 3.0.
 * Money is kept as integer toman values; durations are integer minutes.
 */
enum class WageMode { MONTHLY_SALARY, HOURLY_RATE }

data class WorkSettings(
    val wageMode: WageMode = WageMode.HOURLY_RATE,
    val monthlySalaryToman: Long = 0L,
    val hourlyRateToman: Long = 0L,
    val monthlyRequiredMinutes: Int = 0,
    val dailyRequiredMinutes: Int = 8 * 60,
    val overtimeMultiplier: BigDecimal = BigDecimal("1.4"),
    val settingsVersion: Long = 1L
) {
    init {
        require(monthlySalaryToman >= 0)
        require(hourlyRateToman >= 0)
        require(monthlyRequiredMinutes >= 0)
        require(dailyRequiredMinutes >= 0)
        require(overtimeMultiplier >= BigDecimal.ZERO)
    }

    fun effectiveHourlyRate(): BigDecimal = when (wageMode) {
        WageMode.HOURLY_RATE -> BigDecimal.valueOf(hourlyRateToman)
        WageMode.MONTHLY_SALARY -> if (monthlyRequiredMinutes > 0) {
            BigDecimal.valueOf(monthlySalaryToman)
                .multiply(BigDecimal.valueOf(60))
                .divide(BigDecimal.valueOf(monthlyRequiredMinutes), 8, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO
    }
}

data class OvertimeResult(
    val workedMinutes: Int,
    val overtimeMinutes: Int,
    val hourlyRateToman: BigDecimal,
    val overtimeAmountToman: Long
)

object CalculationEngine {
    /** Parses a duration in H:mm or HH:mm form; hours may exceed 23. */
    fun durationMinutes(value: String): Int {
        val parts = value.split(":")
        require(parts.size == 2)
        val hours = parts[0].toInt()
        val minutes = parts[1].toInt()
        require(hours in 0..999 && minutes in 0..59)
        return Math.addExact(Math.multiplyExact(hours, 60), minutes)
    }

    /**
     * Calculates elapsed work time between local HH:mm values.
     * Overnight shifts are supported. Breaks are supplied as whole minutes.
     */
    fun workedMinutes(start: String, end: String, unpaidBreakMinutes: Int = 0): Int {
        require(unpaidBreakMinutes >= 0)
        val startMinute = parseTime(start)
        val endMinute = parseTime(end)
        val elapsed = if (endMinute >= startMinute) endMinute - startMinute
            else (24 * 60 - startMinute) + endMinute
        return (elapsed - unpaidBreakMinutes).coerceAtLeast(0)
    }

    fun calculate(
        workedMinutes: Int,
        settings: WorkSettings,
        manualOvertimeMinutes: Int? = null,
        manualOvertimeAmountToman: Long? = null
    ): OvertimeResult {
        require(workedMinutes >= 0)
        require(manualOvertimeMinutes == null || manualOvertimeMinutes >= 0)
        require(manualOvertimeAmountToman == null || manualOvertimeAmountToman >= 0)

        val computedMinutes = (workedMinutes - settings.dailyRequiredMinutes).coerceAtLeast(0)
        val finalMinutes = manualOvertimeMinutes ?: computedMinutes
        val rate = settings.effectiveHourlyRate()
        val computedAmount = rate
            .multiply(BigDecimal.valueOf(finalMinutes.toLong()))
            .divide(BigDecimal.valueOf(60), 8, RoundingMode.HALF_UP)
            .multiply(settings.overtimeMultiplier)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()

        return OvertimeResult(
            workedMinutes = workedMinutes,
            overtimeMinutes = finalMinutes,
            hourlyRateToman = rate,
            overtimeAmountToman = manualOvertimeAmountToman ?: computedAmount
        )
    }

    private fun parseTime(value: String): Int {
        val parts = value.split(":")
        require(parts.size == 2)
        val hour = parts[0].toInt()
        val minute = parts[1].toInt()
        require(hour in 0..23 && minute in 0..59)
        return hour * 60 + minute
    }
}
