/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Require a measurable speedup and close agreement with the CPU output. */
internal object AccelerationCheck {
    fun isFaster(cpuNanos: Long, acceleratedNanos: Long): Boolean =
        cpuNanos > 0 && acceleratedNanos > 0 && acceleratedNanos < cpuNanos * 0.85

    fun matches(reference: FloatBuffer, candidate: FloatBuffer): Boolean {
        val a = reference.duplicate()
        val b = candidate.duplicate()
        if (a.remaining() == 0 || a.remaining() != b.remaining()) return false
        val count = a.remaining()
        var squaredError = 0.0
        var squaredSignal = 0.0
        var peak = 0f
        var peakError = 0f
        while (a.hasRemaining()) {
            val x = a.get()
            val y = b.get()
            if (!x.isFinite() || !y.isFinite()) return false
            val error = abs(x - y)
            peakError = max(peakError, error)
            peak = max(peak, abs(x))
            squaredError += error.toDouble() * error
            squaredSignal += x.toDouble() * x
        }
        return sqrt(squaredError / count) <= max(1e-4, sqrt(squaredSignal / count) * 0.01) &&
            peakError <= max(0.01f, peak * 0.05f)
    }
}
