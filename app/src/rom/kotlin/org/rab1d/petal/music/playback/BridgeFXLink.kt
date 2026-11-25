/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID

/**
 * Direct driver for the system-wide BridgeFX delay-line crossfade effect
 * (hardware/bridge/bridgefx). PetalMusic is the queue owner, so unlike the
 * passive BridgeController it can arm transitions at the perfect instant,
 * push the incoming track's profile (op 15) before the boundary and announce
 * the transition to SystemUI.
 *
 * The generic AudioEffect constructor and set/getParameter are hidden APIs.
 * Platform-signed builds (petalOS) are exempt from hidden-API enforcement, so
 * reflection succeeds there; elsewhere attach() simply fails and the internal
 * AutoMix engine takes over.
 */
class BridgeFXLink {
    private var fx: Any? = null
    private var carrier: AudioTrack? = null
    private var setParam: Method? = null
    private var getParam: Method? = null
    private var sessionId = -1
    private var carrierActive = false

    var owner: Long = SecureRandom().nextLong().let { if (it == 0L) 1L else it }
        private set
    var attached: Boolean = false
        private set
    var version: Int = 0
        private set

    /**
     * Attach to [audioSessionId]. A silent looping carrier is created first so
     * the session has a software mixer chain (the controller's trick), then the
     * effect is enabled and ownership claimed before the prior chain is
     * destroyed, so tails survive the handoff.
     */
    fun attach(audioSessionId: Int): Boolean {
        if (attached && sessionId == audioSessionId) return true
        var next: Any? = null
        var clock: AudioTrack? = null
        try {
            clock = silentCarrier(audioSessionId)
            val ctor = AudioEffectClass.getConstructor(UUID::class.java, UUID::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            next = ctor.newInstance(EFFECT_TYPE_NULL, EFFECT_TYPE_BRIDGE, 0, audioSessionId)
            val enable = AudioEffectClass.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
            check(enable.invoke(next, true) == SUCCESS) { "enable failed" }
            setParam = AudioEffectClass.getDeclaredMethod(
                BridgeParams.METHOD_SET, Int::class.javaPrimitiveType, ByteArray::class.java,
            ).apply { isAccessible = true }
            getParam = AudioEffectClass.getDeclaredMethod(
                BridgeParams.METHOD_GET, Int::class.javaPrimitiveType, ByteArray::class.java,
            ).apply { isAccessible = true }
            val diag = get(next, BridgeParams.Ops.DIAGNOSTICS, 11)
            check(diag != null && diag!!.firstOrNull() == 3) { "BridgeFX v3 library required" }
            version = diag.firstOrNull() ?: 0
            check(
                send(
                    next,
                    BridgeParams.Ops.SET_OWNER,
                    owner.toInt(),
                    (owner ushr 32).toInt(),
                ),
            ) { "owner claim failed" }
            val oldFx = fx
            val oldClock = carrier
            clock.play()
            fx = next
            carrier = clock
            carrierActive = true
            sessionId = audioSessionId
            attached = true
            oldClock?.let { runCatching { it.stop(); it.release() } }
            oldFx?.let { runCatching { release(it) } }
            Log.i(TAG, "attached session=$audioSessionId owner=$owner v$version")
            return true
        } catch (e: Exception) {
            Log.i(TAG, "BridgeFX not attachable on session=$audioSessionId (${e.javaClass.simpleName})")
            runCatching { clock?.release() }
            next?.let { runCatching { release(it) } }
            attached = false
            setParam = null
            getParam = null
            return false
        }
    }

    fun detach() {
        fx?.let { runCatching { send(it, BridgeParams.Ops.ABORT) } }
        fx?.let { f -> runCatching { AudioEffectClass.getMethod("release").invoke(f) } }
        fx = null
        carrier?.let { runCatching { it.stop() } }
        carrier?.release()
        carrier = null
        carrierActive = false
        sessionId = -1
        attached = false
    }

    fun setPlaybackActive(active: Boolean) {
        if (!attached || carrierActive == active) return
        val track = carrier ?: return
        runCatching {
            if (active) track.play() else track.pause()
            carrierActive = active
        }
    }

    fun setFade(ms: Int): Boolean = send(BridgeParams.Ops.SET_FADE, ms.coerceIn(250, 12000))
    fun setStyle(style: Int): Boolean = send(BridgeParams.Ops.SET_STYLE, style.coerceIn(0, 3))
    fun arm(countdownMs: Int): Boolean = send(BridgeParams.Ops.ARM, countdownMs.coerceIn(0, 12000))
    fun abort(): Boolean = send(BridgeParams.Ops.ABORT)

    /**
     * Trigger a transition now. [introAgeMs] locates the incoming track's start
     * in history when metadata arrives late; [transitionId] makes retries and
     * rapid skips safe (monotonic, survives cancellation).
     */
    fun boundary(introAgeMs: Int, transitionId: Int): Boolean =
        send(BridgeParams.Ops.BOUNDARY, introAgeMs.coerceIn(0, 1000), transitionId)

    /** Push an incoming-track profile: 7..8 ints, format from BridgeController. */
    fun pushProfile(profile: IntArray): Boolean =
        profile.size in 7..8 && send(BridgeParams.Ops.PUSH_PROFILE, *profile)

    /** Live analysis of the current stream (14 ints). */
    fun analysis(): IntArray? = get(fx, BridgeParams.Ops.ANALYSIS, 14)

    fun diagnostics(): IntArray? = get(fx, BridgeParams.Ops.DIAGNOSTICS, 12)

    fun renderStats(): IntArray? = get(fx, BridgeParams.Ops.RENDER, 4)

    private fun send(op: Int, vararg values: Int): Boolean =
        fx?.let { send(it, op, *values) } ?: false

    private fun send(effect: Any, op: Int, vararg values: Int): Boolean {
        val m = setParam ?: return false
        return runCatching {
            m.invoke(effect, BridgeParams.PARAM_BASE, BridgeParams.packSet(op, values)) == SUCCESS
        }.getOrDefault(false)
    }

    private fun get(effect: Any?, op: Int, count: Int): IntArray? {
        val m = getParam ?: return null
        return runCatching {
            val data = ByteArray(count * 4)
            val bytes = m.invoke(effect, BridgeParams.PARAM_BASE + op, data) as Int
            if (bytes != data.size) null else BridgeParams.unpackGet(data)
        }.getOrNull()
    }

    private fun release(effect: Any) {
        AudioEffectClass.getMethod("release").invoke(effect)
    }

    private fun silentCarrier(session: Int): AudioTrack {
        val frames = 4800
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setSessionId(session)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(frames * 4)
            .build()
        try {
            check(track.state == AudioTrack.STATE_NO_STATIC_DATA)
            check(track.write(ShortArray(frames * 2), 0, frames * 2) == frames * 2)
            check(track.setLoopPoints(0, frames, -1) == AudioTrack.SUCCESS)
            return track
        } catch (e: Exception) {
            track.release()
            throw e
        }
    }

    companion object {
        const val TAG = "PetalMusic/BridgeFX"

        /** bridgefx effect UUID (type == impl), from BridgeFX.h. */
        val EFFECT_TYPE_BRIDGE: UUID = UUID.fromString("c105f001-b2d5-4a19-8e77-1c5f0b31a5d5")

        /** AudioEffect.EFFECT_TYPE_NULL — hidden constant, all-zero UUID. */
        val EFFECT_TYPE_NULL: UUID = UUID(0, 0)

        /** AudioEffect.SUCCESS == 0. */
        const val SUCCESS = 0

        const val ACTION_TRANSITION = "org.rab1d.bridge.action.TRANSITION"
        const val EXTRA_DURATION = "duration"

        /** Tell SystemUI a crossfade started so it can do the pretty stuff. */
        fun announce(context: Context, fadeMs: Int) {
            val intent = Intent(ACTION_TRANSITION)
                .setPackage("com.android.systemui")
                .putExtra(EXTRA_DURATION, fadeMs)
            runCatching { context.sendBroadcast(intent) }
        }
    }
}

private val AudioEffectClass by lazy { Class.forName("android.media.audiofx.AudioEffect") }
