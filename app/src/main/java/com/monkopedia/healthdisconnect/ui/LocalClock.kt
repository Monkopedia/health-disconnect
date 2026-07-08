package com.monkopedia.healthdisconnect.ui

import androidx.compose.runtime.staticCompositionLocalOf
import java.time.Clock

/**
 * The clock the UI reads for "now": the chart's continuous time axis and the "Last refreshed"
 * timestamp. Defaults to the real system clock; screenshot tests override it with a fixed clock so
 * renders are deterministic (data lands where intended and the header timestamp doesn't drift
 * run-to-run). Kept as a CompositionLocal so the frozen clock is provided once at the theme root
 * rather than threaded through every chart signature.
 */
val LocalClock = staticCompositionLocalOf<Clock> { Clock.systemDefaultZone() }
