/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Native graph compilation lives here, never in the player process. */
class NpuPreparationService : Service() {
    companion object {
        const val START = 1
        const val ABORT = 2
        const val PROGRESS = 3
        const val DONE = 4
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var started = false
    private val clients = ConcurrentHashMap<IBinder, Messenger>()
    private val currentBlock = AtomicInteger(0)
    @Volatile private var finished = false
    @Volatile private var finalError: String? = null
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                ABORT -> {
                    msg.replyTo?.binder?.let { clients.remove(it) }
                    if (clients.isEmpty()) Process.killProcess(Process.myPid())
                }
                START -> {
                    val reply = msg.replyTo ?: return
                    clients[reply.binder] = reply
                    if (finished) {
                        send(reply, DONE, error = finalError)
                    } else if (started) {
                        send(reply, PROGRESS, currentBlock.get())
                    } else {
                        started = true
                        executor.execute { prepare() }
                    }
                }
            }
        }
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun send(reply: Messenger, what: Int, index: Int = 0, error: String? = null) {
        val message = Message.obtain(null, what, index, 0)
        message.data = Bundle().apply { putString("error", error) }
        reply.send(message)
    }

    private fun broadcast(what: Int, index: Int = 0, error: String? = null) {
        for ((binder, reply) in clients) {
            runCatching { send(reply, what, index, error) }.onFailure { clients.remove(binder) }
        }
    }

    private fun prepare() {
        try {
            val files = UpscaleModelRepository(this).pipelineFiles()
                ?: error("NPU block models are unavailable")
            val env = OrtEnvironment.getEnvironment()
            for ((index, block) in files.npuBlocks.withIndex()) {
                currentBlock.set(index)
                broadcast(PROGRESS, index)
                if (block.cache.isFile) continue
                val part = File(block.cache.parentFile, block.cache.nameWithoutExtension + ".part.onnx")
                part.delete()
                try {
                    val start = System.nanoTime()
                    Log.i(OnnxAudioUpscaler.TAG, "compiling NPU block ${index + 1}/${files.npuBlocks.size}")
                    OrtSession.SessionOptions().use { options ->
                        options.setIntraOpNumThreads(1)
                        QnnSessionOptions.configure(options, applicationInfo.nativeLibraryDir, part)
                        env.createSession(block.model.absolutePath, options).use { }
                    }
                    check(part.length() > 0) { "compiler did not save block ${index + 1}" }
                    check(part.renameTo(block.cache)) { "could not publish NPU block cache" }
                    Log.i(OnnxAudioUpscaler.TAG, "NPU block ${index + 1} compiled in ${(System.nanoTime() - start) / 1_000_000}ms")
                } finally {
                    part.delete()
                }
            }
            finished = true
            broadcast(DONE)
        } catch (e: Exception) {
            Log.w(OnnxAudioUpscaler.TAG, "NPU preparation failed", e)
            finalError = e.message ?: "NPU compiler failed"
            finished = true
            broadcast(DONE, error = finalError)
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
        // Interrupting a Java thread cannot stop graphFinalize in native code.
        // Destroy only this compiler process, freeing its native heaps and RPCs.
        Process.killProcess(Process.myPid())
    }
}
