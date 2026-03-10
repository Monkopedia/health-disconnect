package com.monkopedia.healthdisconnect

import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.reflect.KClass

object DemoRecordFactory {

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)

    /**
     * Seeded PRNG that produces deterministic values per seed.
     * Returns a value in [-1.0, 1.0].
     */
    private fun hash(seed: Long): Double {
        var h = seed xor (seed ushr 16)
        h = h * 0x45d9f3b + 0x1234567
        h = h xor (h ushr 16)
        h = h * 0x45d9f3b
        h = h xor (h ushr 16)
        return (h and 0x7FFFFFFF).toDouble() / 0x7FFFFFFF.toDouble() * 2.0 - 1.0
    }

    /** Returns a value in [0.0, 1.0] */
    private fun hash01(seed: Long): Double = (hash(seed) + 1.0) / 2.0

    /** Returns a value in [-amplitude, +amplitude] */
    private fun jitter(seed: Long, amplitude: Double): Double = hash(seed) * amplitude

    fun generate(cls: KClass<out Record>, start: Instant, end: Instant): List<Record> {
        return when (cls) {
            WeightRecord::class -> generateWeight(start, end)
            StepsRecord::class -> generateSteps(start, end)
            HeartRateRecord::class -> generateHeartRate(start, end)
            RestingHeartRateRecord::class -> generateRestingHeartRate(start, end)
            SleepSessionRecord::class -> generateSleep(start, end)
            BodyFatRecord::class -> generateBodyFat(start, end)
            DistanceRecord::class -> generateDistance(start, end)
            else -> emptyList()
        }
    }

    private fun daysIn(start: Instant, end: Instant): List<LocalDate> {
        val startDate = start.atZone(zone).toLocalDate()
        val endDate = end.atZone(zone).toLocalDate()
        return generateSequence(startDate) { it.plusDays(1) }
            .takeWhile { !it.isAfter(endDate) }
            .toList()
    }

    private fun dayFraction(date: LocalDate, totalDays: Long = 90): Double {
        return ChronoUnit.DAYS.between(today.minusDays(totalDays), date).toDouble()
            .coerceIn(0.0, totalDays.toDouble()) / totalDays.toDouble()
    }

    /**
     * Weight: 83.5 → 76.2 kg over 90 days.
     * - Fast initial drop (water weight), then slower steady loss
     * - Weekly fluctuation pattern (heavier Mon after weekend eating, lighter Fri)
     * - A 2-week plateau around day 35-50
     * - Occasional missed weigh-ins (skip ~10% of days)
     * - Morning readings between 6:30-7:30 AM
     */
    private fun generateWeight(start: Instant, end: Instant): List<Record> {
        val days = daysIn(start, end)
        val totalDays = 90L
        return days.mapNotNull { date ->
            val epoch = date.toEpochDay()
            // Skip ~10% of days (forgot to weigh)
            if (hash01(epoch * 137) < 0.10) return@mapNotNull null

            val t = dayFraction(date, totalDays)

            // Exponential-ish curve: fast drop early, slower later
            // Pronounced plateau + slight regain around days 30-50 (t=0.33..0.56)
            val baseCurve = 83.5 - 4.5 * t - 2.8 * t * t // ends ~76.2
            val plateau = if (t in 0.33..0.56) {
                1.8 * sin((t - 0.33) * PI / 0.23) // visible stall + bump
            } else 0.0

            // Weekly cycle: heavier Mon/Tue, lighter Thu/Fri
            val dow = date.dayOfWeek.value
            val weeklyCycle = when (dow) {
                1 -> 0.5   // Monday: heavier after weekend
                2 -> 0.3
                3 -> 0.0
                4 -> -0.2
                5 -> -0.4  // Friday: lightest
                6 -> -0.1
                7 -> 0.3   // Sunday: creeping back up
                else -> 0.0
            }

            val noise = jitter(epoch * 31, 0.25)
            val weight = (baseCurve + plateau + weeklyCycle + noise).coerceIn(74.0, 85.0)

            // Weigh-in time varies 6:30-7:29
            val totalMinutes = 6 * 60 + 30 + (hash01(epoch * 43) * 59).toInt()
            val time = date.atTime(totalMinutes / 60, totalMinutes % 60).atZone(zone).toInstant()
            WeightRecord(
                weight = Mass.kilograms(weight),
                time = time,
                zoneOffset = zone.rules.getOffset(time),
                metadata = Metadata.manualEntry()
            )
        }
    }

    /**
     * Steps: realistic daily patterns over 30 days.
     * - Weekday commuter: 7k-11k (office worker who walks to transit)
     * - Weekends: bimodal — either lazy (2k-4k) or active outing (12k-18k)
     * - One big hike day (~22k) in the last 30 days
     * - A sick/rest stretch of 2-3 days with very low counts
     * - Rainy days slightly lower
     */
    private fun generateSteps(start: Instant, end: Instant): List<Record> {
        val days = daysIn(start, end)
        // Pick a "hike day" and a "sick start" deterministically
        val hikeDay = today.minusDays(12)
        val sickStart = today.minusDays(22)
        val sickEnd = sickStart.plusDays(2)

        return days.map { date ->
            val epoch = date.toEpochDay()
            val dow = date.dayOfWeek.value
            val isWeekend = dow >= 6

            val count: Long = when {
                date == hikeDay -> {
                    // Big hike day
                    (21000 + jitter(epoch * 51, 2000.0)).roundToLong()
                }
                date in sickStart..sickEnd -> {
                    // Sick days
                    (1500 + hash01(epoch * 61) * 1500).roundToLong()
                }
                isWeekend -> {
                    // Bimodal: 40% chance of active weekend day
                    if (hash01(epoch * 71) < 0.40) {
                        (13000 + jitter(epoch * 73, 3000.0)).roundToLong()
                    } else {
                        (3000 + hash01(epoch * 77) * 2500).roundToLong()
                    }
                }
                else -> {
                    // Weekday: normal commuter pattern with some variance
                    val base = 8500.0
                    // Some days you take the long route, some days you don't
                    val walkBonus = if (hash01(epoch * 79) < 0.3) 2500.0 else 0.0
                    (base + walkBonus + jitter(epoch * 83, 1500.0)).roundToLong()
                }
            }.coerceAtLeast(800)

            val startTime = date.atTime(6, 30).atZone(zone).toInstant()
            val endTime = date.atTime(22, 30).atZone(zone).toInstant()
            val offset = zone.rules.getOffset(startTime)
            StepsRecord(
                count = count,
                startTime = startTime,
                endTime = endTime,
                startZoneOffset = offset,
                endZoneOffset = offset,
                metadata = Metadata.manualEntry()
            )
        }
    }

    /**
     * Heart rate: daily average readings with realistic patterns.
     * - Base resting ~70 bpm, trending down slightly as fitness improves
     * - Higher on active/hike days, lower on rest days
     * - Elevated during sick days
     * - Morning caffeine spike some days
     * - Weekly stress pattern (higher Wed/Thu)
     */
    private fun generateHeartRate(start: Instant, end: Instant): List<Record> {
        val days = daysIn(start, end)
        val sickStart = today.minusDays(22)
        val sickEnd = sickStart.plusDays(2)

        return days.map { date ->
            val epoch = date.toEpochDay()
            val t = dayFraction(date)
            val dow = date.dayOfWeek.value

            // Slight fitness improvement trend
            val trend = 72.0 - 3.0 * t

            // Mid-week stress bump
            val stressCycle = when (dow) {
                3 -> 3.0   // Wednesday
                4 -> 4.0   // Thursday peak
                5 -> 2.0   // Friday
                6 -> -2.0  // Weekend recovery
                7 -> -3.0
                else -> 0.0
            }

            // Sick days: elevated HR
            val sickBump = if (date in sickStart..sickEnd) 8.0 + jitter(epoch * 89, 3.0) else 0.0

            val noise = jitter(epoch * 97, 2.5)
            val bpm = (trend + stressCycle + sickBump + noise).roundToLong().coerceIn(55, 105)

            // Multiple samples through the day to look more realistic
            val sampleCount = 3 + (hash01(epoch * 101) * 4).toInt() // 3-6 samples
            val baseTime = date.atTime(8, 0).atZone(zone).toInstant()
            val samples = (0 until sampleCount).map { i ->
                val sampleTime = baseTime.plusSeconds(i * 3600L + (hash01(epoch * (103 + i)) * 1800).toLong())
                val sampleVariance = jitter(epoch * (107 + i), 5.0)
                HeartRateRecord.Sample(
                    time = sampleTime,
                    beatsPerMinute = (bpm + sampleVariance.roundToLong()).coerceIn(50, 120)
                )
            }

            val offset = zone.rules.getOffset(baseTime)
            HeartRateRecord(
                startTime = samples.first().time,
                endTime = samples.last().time.plusSeconds(1),
                startZoneOffset = offset,
                endZoneOffset = offset,
                samples = samples,
                metadata = Metadata.manualEntry()
            )
        }
    }

    /**
     * Resting heart rate: measured once daily in the morning.
     * - Gradual improvement from ~66 to ~60 bpm over 90 days
     * - Higher after poor sleep or alcohol (correlated with weekend)
     * - Spike during sick days
     * - Not perfectly linear — comes in waves
     */
    private fun generateRestingHeartRate(start: Instant, end: Instant): List<Record> {
        val days = daysIn(start, end)
        val sickStart = today.minusDays(22)
        val sickEnd = sickStart.plusDays(2)

        return days.mapNotNull { date ->
            val epoch = date.toEpochDay()
            // Skip ~5% of days
            if (hash01(epoch * 109) < 0.05) return@mapNotNull null

            val t = dayFraction(date)
            val dow = date.dayOfWeek.value

            // Non-linear improvement with a stall in the middle
            val trend = 66.0 - 4.0 * t - 2.0 * sin(t * PI) * t

            // Post-weekend bump (poor sleep / alcohol)
            val weekendEffect = when (dow) {
                1 -> 3.0  // Monday: recovery from weekend
                7 -> 2.0  // Sunday: stayed up late
                else -> 0.0
            }

            // Sick bump
            val sickBump = if (date in sickStart..sickEnd) 6.0 else 0.0

            val noise = jitter(epoch * 113, 1.5)
            val bpm = (trend + weekendEffect + sickBump + noise).roundToLong().coerceIn(50, 78)

            val minuteOffset = (hash01(epoch * 127) * 30).toInt()
            val time = date.atTime(7, 15 + minuteOffset).atZone(zone).toInstant()
            RestingHeartRateRecord(
                beatsPerMinute = bpm,
                time = time,
                zoneOffset = zone.rules.getOffset(time),
                metadata = Metadata.manualEntry()
            )
        }
    }

    /**
     * Sleep: realistic nightly patterns.
     * - Base 7-7.5 hrs, weekday discipline vs weekend variability
     * - Friday/Saturday nights: later bedtime, sometimes longer sleep (catch-up)
     * - A couple of really bad nights (insomnia: 3-4 hrs)
     * - Sick days: extra long sleep (9-10 hrs)
     * - Gradual improvement in consistency over time
     * - Bedtime varies: 21:30-23:30 range
     */
    private fun generateSleep(start: Instant, end: Instant): List<Record> {
        val days = daysIn(start, end)
        val sickStart = today.minusDays(22)
        val sickEnd = sickStart.plusDays(2)
        // Pick 2 insomnia nights
        val insomnia1 = today.minusDays(18)
        val insomnia2 = today.minusDays(7)

        return days.mapNotNull { date ->
            val epoch = date.toEpochDay()
            val dow = date.dayOfWeek.value // day the sleep session ENDS

            val (bedtimeHour, bedtimeMin, sleepHours) = when {
                date in sickStart..sickEnd -> {
                    // Sick: early bedtime, long sleep
                    Triple(21, 0 + (hash01(epoch * 131) * 30).toInt(), 9.0 + hash01(epoch * 137) * 1.5)
                }
                date == insomnia1 || date == insomnia2 -> {
                    // Insomnia: late to bed, short sleep
                    Triple(23, 30 + (hash01(epoch * 139) * 30).toInt(), 3.5 + hash01(epoch * 149) * 1.0)
                }
                dow == 6 || dow == 7 -> {
                    // Friday/Saturday night: later bedtime
                    Triple(23, (hash01(epoch * 151) * 60).toInt(), 7.5 + hash01(epoch * 157) * 2.0)
                }
                dow == 1 -> {
                    // Sunday night: early to bed, decent sleep
                    Triple(22, (hash01(epoch * 163) * 30).toInt(), 7.0 + hash01(epoch * 167) * 0.5)
                }
                else -> {
                    // Weeknight: disciplined
                    Triple(22, 15 + (hash01(epoch * 173) * 45).toInt(), 6.5 + hash01(epoch * 179) * 1.2)
                }
            }

            val bedtime = date.minusDays(1).atTime(bedtimeHour, bedtimeMin.coerceIn(0, 59))
                .atZone(zone).toInstant()
            val sleepMinutes = (sleepHours * 60).roundToLong()
            val wakeTime = bedtime.plus(sleepMinutes, ChronoUnit.MINUTES)

            if (wakeTime.isBefore(bedtime)) return@mapNotNull null

            val offset = zone.rules.getOffset(bedtime)
            SleepSessionRecord(
                startTime = bedtime,
                endTime = wakeTime,
                startZoneOffset = offset,
                endZoneOffset = offset,
                metadata = Metadata.manualEntry()
            )
        }
    }

    /**
     * Body fat: 23.8% → 19.5% over 90 days.
     * - Lags behind weight loss (body recomp takes time)
     * - Measurement noise is higher than weight (body fat scales are imprecise)
     * - Hydration affects readings: post-workout readings skew low, morning readings more stable
     * - Plateau around weeks 5-7 while weight plateau happens
     * - Only measured every 2-3 days (not daily)
     */
    private fun generateBodyFat(start: Instant, end: Instant): List<Record> {
        val days = daysIn(start, end)
        val totalDays = 90L
        return days.mapNotNull { date ->
            val epoch = date.toEpochDay()
            // Only measure every 2-3 days
            if ((epoch % 3 != 0L) && hash01(epoch * 181) < 0.6) return@mapNotNull null

            val t = dayFraction(date, totalDays)

            // S-curve: slow start, faster in middle, slow at end
            val curve = 23.8 - 4.3 * (3 * t * t - 2 * t * t * t) // smooth S from 23.8 to 19.5

            // Plateau bump correlated with weight (lags by ~5 days)
            val plateau = if (t in 0.38..0.61) {
                1.2 * sin((t - 0.38) * PI / 0.23)
            } else 0.0

            // Body fat scale noise is quite high
            val measurementNoise = jitter(epoch * 191, 0.7)
            // Hydration effect
            val hydration = jitter(epoch * 193, 0.4)

            val pct = (curve + plateau + measurementNoise + hydration).coerceIn(17.0, 26.0)

            val minuteOffset = (hash01(epoch * 197) * 20).toInt()
            val time = date.atTime(7, 5 + minuteOffset).atZone(zone).toInstant()
            BodyFatRecord(
                percentage = Percentage(pct),
                time = time,
                zoneOffset = zone.rules.getOffset(time),
                metadata = Metadata.manualEntry()
            )
        }
    }

    /**
     * Distance: correlated with steps but not perfectly (stride length varies).
     * - Walking stride ~0.75m, running stride ~1.1m
     * - Hike day has long distance
     * - Sick days very low
     * - Some weekend days include a run (longer stride = more km per step)
     */
    private fun generateDistance(start: Instant, end: Instant): List<Record> {
        val days = daysIn(start, end)
        val hikeDay = today.minusDays(12)
        val sickStart = today.minusDays(22)
        val sickEnd = sickStart.plusDays(2)

        return days.map { date ->
            val epoch = date.toEpochDay()
            val dow = date.dayOfWeek.value
            val isWeekend = dow >= 6

            val km: Double = when {
                date == hikeDay -> 16.5 + jitter(epoch * 199, 1.5)
                date in sickStart..sickEnd -> 0.8 + hash01(epoch * 211) * 1.0
                isWeekend -> {
                    if (hash01(epoch * 71) < 0.40) {
                        // Active weekend: includes a run
                        9.0 + jitter(epoch * 213, 2.5)
                    } else {
                        2.0 + hash01(epoch * 217) * 2.0
                    }
                }
                else -> {
                    // Weekday commute walking
                    val base = 5.5
                    val bonus = if (hash01(epoch * 79) < 0.3) 2.0 else 0.0
                    base + bonus + jitter(epoch * 223, 1.0)
                }
            }.coerceAtLeast(0.3)

            val startTime = date.atTime(6, 30).atZone(zone).toInstant()
            val endTime = date.atTime(22, 30).atZone(zone).toInstant()
            val offset = zone.rules.getOffset(startTime)
            DistanceRecord(
                distance = Length.kilometers(km),
                startTime = startTime,
                endTime = endTime,
                startZoneOffset = offset,
                endZoneOffset = offset,
                metadata = Metadata.manualEntry()
            )
        }
    }
}
