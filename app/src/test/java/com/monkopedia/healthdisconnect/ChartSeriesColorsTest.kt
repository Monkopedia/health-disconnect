package com.monkopedia.healthdisconnect

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartSeriesColorsTest {
    @Test
    fun chartSeriesColors_darkThemeUsesProvidedPrimaryColor() {
        val primary = 0xFF123456.toInt()
        val colors = chartSeriesColors(
            theme = GraphShareTheme.DARK,
            primaryColor = primary,
            secondaryColor = 0xFF654321.toInt()
        )

        assertEquals(primary, colors.first())
        assertEquals(0xFFD1B3FF.toInt(), colors[1])
        assertEquals(0xFFFFB74D.toInt(), colors[2])
    }

    @Test
    fun chartSeriesColors_lightThemeUsesProvidedPrimaryAndSecondaryColors() {
        val primary = 0xFF010203.toInt()
        val secondary = 0xFF040506.toInt()
        val colors = chartSeriesColors(
            theme = GraphShareTheme.LIGHT,
            primaryColor = primary,
            secondaryColor = secondary
        )

        assertEquals(primary, colors.first())
        assertEquals(0xFF7E57C2.toInt(), colors[1])
        assertEquals(secondary, colors[2])
    }
}
