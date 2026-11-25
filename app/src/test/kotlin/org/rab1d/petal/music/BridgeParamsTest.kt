/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rab1d.petal.music.playback.BridgeParams
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire-compatibility tests against the effect_param_t layout decoded by
 * BridgeFX.cpp (setParameterSpecific / getParameterSpecific).
 */
class BridgeParamsTest {

    @Test
    fun `param base is BRID`() {
        assertEquals(0x42524944, BridgeParams.PARAM_BASE)
    }

    @Test
    fun `set blob matches effect_param_t value layout`() {
        // value = [op, v1, v2...] little-endian, exactly what BridgeFX decodes
        // at kValueOff with rdI32.
        val blob = BridgeParams.packSet(BridgeParams.Ops.BOUNDARY, intArrayOf(250, 7))
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(12, buf.int) // op 12 = boundary
        assertEquals(250, buf.int) // introAgeMs
        assertEquals(7, buf.int) // transitionId
        assertEquals(3 * 4, blob.size)
    }

    @Test
    fun `owner handoff packs lo-hi 64 bit`() {
        val owner = 0x1122334455667788L
        val lo = owner.toInt()
        val hi = (owner ushr 32).toInt()
        val blob = BridgeParams.packSet(BridgeParams.Ops.SET_OWNER, intArrayOf(lo, hi))
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(14, buf.int)
        val rebuilt = (buf.int.toLong() and 0xFFFFFFFFL) or
            ((buf.int.toLong() and 0xFFFFFFFFL) shl 32)
        assertEquals(owner, rebuilt)
    }

    @Test
    fun `get key encodes op above base`() {
        val key = BridgeParams.PARAM_BASE + BridgeParams.Ops.DIAGNOSTICS
        assertEquals(BridgeParams.PARAM_BASE + 13, key)
        // and decode side returns ints little-endian, zero-padded by the HAL
        val data = ByteArray(11 * 4)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(3).putInt(0).putInt(2).putInt(0).putInt(550)
        val ints = BridgeParams.unpackGet(data)
        assertEquals(3, ints[0])
        assertEquals(550, ints[4])
    }

    @Test
    fun `fade is op 1 within bounds`() {
        val blob = BridgeParams.packSet(BridgeParams.Ops.SET_FADE, intArrayOf(10000))
        assertEquals(1, ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).int)
        assertTrue(250 <= 10000 && 10000 <= 12000)
    }

    @Test
    fun `profile push takes 7 or 8 ints`() {
        val profile = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertTrue(profile.size in 7..8)
        val blob = BridgeParams.packSet(BridgeParams.Ops.PUSH_PROFILE, profile)
        assertEquals(9 * 4, blob.size)
        assertArrayEquals(intArrayOf(15, 1, 2, 3, 4, 5, 6, 7, 8), BridgeParams.unpackGet(blob))
    }
}
