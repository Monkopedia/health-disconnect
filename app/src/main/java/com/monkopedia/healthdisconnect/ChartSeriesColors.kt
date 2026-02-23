package com.monkopedia.healthdisconnect

private val DefaultChartPrimaryColor = 0xFFF57C00.toInt()
private val DefaultChartSecondaryColor = 0xFFBF360C.toInt()

internal fun chartSeriesColors(
    theme: GraphShareTheme,
    primaryColor: Int,
    secondaryColor: Int
): List<Int> {
    return if (theme == GraphShareTheme.DARK) {
        listOf(
            primaryColor,
            0xFFD1B3FF.toInt(),
            0xFFFFB74D.toInt(),
            0xFF81C784.toInt(),
            0xFF4FC3F7.toInt(),
            0xFFE57373.toInt()
        )
    } else {
        listOf(
            primaryColor,
            0xFF7E57C2.toInt(),
            secondaryColor,
            0xFF2E7D32.toInt(),
            0xFFC62828.toInt(),
            0xFF1565C0.toInt()
        )
    }
}

internal fun defaultChartSeriesColors(theme: GraphShareTheme): List<Int> {
    return chartSeriesColors(
        theme = theme,
        primaryColor = DefaultChartPrimaryColor,
        secondaryColor = DefaultChartSecondaryColor
    )
}
