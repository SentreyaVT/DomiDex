package com.dominik.cardvault.ui

import kotlin.math.sqrt

internal data class ScannerCameraCapabilities(
    val isRear: Boolean,
    val supportsAutoFocus: Boolean,
    val focalLengthMm: Float?,
    val sensorWidthMm: Float?,
    val sensorHeightMm: Float?,
    val maxOutputPixels: Long,
) {
    /** Sensor-relative focal length avoids treating a small-sensor main lens as ultrawide. */
    val equivalentFocalLengthMm: Float? get() {
        val focal = focalLengthMm?.takeIf { it.isFinite() && it > 0f } ?: return null
        val width = sensorWidthMm?.takeIf { it.isFinite() && it > 0f } ?: return null
        val height = sensorHeightMm?.takeIf { it.isFinite() && it > 0f } ?: return null
        return focal * 43.27f / sqrt(width * width + height * height)
    }

    val lensSuitability: Int get() = equivalentFocalLengthMm.let { equivalent ->
        when {
            equivalent == null -> 2
            equivalent in 20f..40f -> 3
            equivalent < 20f -> 0
            else -> 1
        }
    }
}

internal val scannerCameraPreference = compareByDescending<ScannerCameraCapabilities> { it.isRear }
    .thenByDescending { it.supportsAutoFocus }
    .thenByDescending { it.lensSuitability }
    // 24 MP already exceeds the recognition plane; advertising 108 MP should not beat AF/optics.
    .thenByDescending { it.maxOutputPixels.coerceIn(0L, 24_000_000L) }
