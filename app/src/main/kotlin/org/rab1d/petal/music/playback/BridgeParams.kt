/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * effect_param_t packing for the BridgeFX 'BRID' vendor-extension channel,
 * byte-compatible with audio_effect.h and the BridgeController. Kept free of
 * android imports so the wire format is unit-testable.
 */
object BridgeParams {
    /** 'BRID' parameter channel base (BridgeFX.cpp). */
    const val PARAM_BASE = 0x42524944

    /** legacy AudioEffect.set/getParameter(int param, byte[] data) — hidden API. */
    const val METHOD_SET = "setParameter"
    const val METHOD_GET = "getParameter"

    /** Pack a SET request: [op, values...] as the value blob. */
    fun packSet(op: Int, values: IntArray): ByteArray {
        val buf = ByteBuffer.allocate((values.size + 1) * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(op)
        values.forEach { buf.putInt(it) }
        return buf.array()
    }

    /** Pack a GET request payload (returned ints). */
    fun unpackGet(data: ByteArray): IntArray {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(data.size / 4) { buf.int }
    }

    /**
     * Command ids (BridgeRuntime control/query dispatch):
     * 1=setFadeMs 2=armTransition 8=reset 9=setStyle 12=boundary 14=owner
     * 11=analysis 13=diagnostics 15=incoming profile 17=render stats.
     */
    object Ops {
        const val SET_FADE = 1
        const val ARM = 2
        const val ABORT = 8
        const val SET_STYLE = 9
        const val ANALYSIS = 11
        const val BOUNDARY = 12
        const val DIAGNOSTICS = 13
        const val SET_OWNER = 14
        const val PUSH_PROFILE = 15
        const val RENDER = 17
    }
}
