package ir.example.karkard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class CalculationEngineTest {
    @Test
    fun parsesMinutePrecisionDuration() {
        assertEquals(485, CalculationEngine.durationMinutes("08:05"))
        assertEquals(1500, CalculationEngine.durationMinutes("25:00"))
    }

    @Test
    fun calculatesOvertimeToTheMinute() {
        val settings = WorkSettings(
            wageMode = WageMode.HOURLY_RATE,
            hourlyRateToman = 20_000,
            dailyRequiredMinutes = 480,
            overtimeMultiplier = BigDecimal("1.4")
        )
        val result = CalculationEngine.calculate(485, settings)
        assertEquals(5, result.overtimeMinutes)
        assertEquals(2333L, result.overtimeAmountToman)
    }

    @Test
    fun derivesHourlyRateFromMonthlySalary() {
        val settings = WorkSettings(
            wageMode = WageMode.MONTHLY_SALARY,
            monthlySalaryToman = 3_000_000,
            monthlyRequiredMinutes = 9_000,
            dailyRequiredMinutes = 480,
            overtimeMultiplier = BigDecimal("1.4")
        )
        assertEquals(BigDecimal("20000.00000000"), settings.effectiveHourlyRate())
        assertEquals(56_000L, CalculationEngine.calculate(600, settings).overtimeAmountToman)
    }

    @Test
    fun manualDurationAndAmountOverridesAreIndependent() {
        val settings = WorkSettings(hourlyRateToman = 10_000)
        val manualDuration = CalculationEngine.calculate(600, settings, manualOvertimeMinutes = 75)
        assertEquals(75, manualDuration.overtimeMinutes)
        assertEquals(17_500L, manualDuration.overtimeAmountToman)

        val manualAmount = CalculationEngine.calculate(600, settings, manualOvertimeAmountToman = 123_456)
        assertEquals(120, manualAmount.overtimeMinutes)
        assertEquals(123_456L, manualAmount.overtimeAmountToman)
    }

    @Test
    fun supportsOvernightShiftAndBreak() {
        assertEquals(450, CalculationEngine.workedMinutes("22:00", "06:00", 30))
    }
}
