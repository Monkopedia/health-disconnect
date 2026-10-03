package com.monkopedia.healthdisconnect.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Camera distance, in multiples of display density, used for the perspective projection of the
 * swipe tilt on `DataViewAdapter`'s pager pages. This is the value users get.
 */
const val PAGER_CAMERA_DISTANCE = 24f

/**
 * The camera distance the pager page tilt is projected through, in multiples of display density.
 *
 * Defaults to [PAGER_CAMERA_DISTANCE], so the shipped app is unchanged. The screenshot harness
 * overrides it with [Float.POSITIVE_INFINITY], which is the affine limit of the projection: the
 * page still rotates, it is just no longer rendered through a perspective divide. What survives in
 * the baseline is the rotation's own affine part, a horizontal squeeze of cos(t) — measured at
 * ~1 px of edge displacement at the ±7° this uses, enough for an exact pixel comparison to catch a
 * regression in the tilt but not enough for a human to see it. See issue #73 —
 * that divide is `RCPPS` + one Newton step, an implementation-defined instruction whose last
 * mantissa bit differs between CPU vendors, which made 12 baselines oscillate between two states
 * across the CI fleet. Kept as a CompositionLocal for the same reason as [LocalClock]: the harness
 * provides it once at the render root instead of it being threaded through the composable's
 * signature.
 */
val LocalPagerCameraDistance = staticCompositionLocalOf { PAGER_CAMERA_DISTANCE }
