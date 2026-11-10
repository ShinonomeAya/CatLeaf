package dev.local.catleaf.nativeapp.ui.reader

/** Coordinates are viewport pixels; overlap keeps a cut line readable on the next screen. */
internal fun readerPageTarget(y: Int, viewport: Int, overlap: Int, maxY: Int, direction: Int): Int {
    if (viewport <= 0) return y.coerceIn(0, maxY.coerceAtLeast(0))
    val step = viewport - if (overlap in 0 until viewport) overlap else 0
    return (y.toLong() + step.toLong() * direction.coerceIn(-1, 1))
        .coerceIn(0, maxY.coerceAtLeast(0).toLong()).toInt()
}

internal fun readerPageDirection(x: Float, width: Float): Int = when {
    width <= 0 || !x.isFinite() || x < 0 || x > width -> 0
    x < width * .3f -> -1
    x > width * .7f -> 1
    else -> 0
}
