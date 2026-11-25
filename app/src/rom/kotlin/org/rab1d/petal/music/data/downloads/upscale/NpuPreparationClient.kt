/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal object NpuPreparationClient {
    /** Bounds native compilation even when it hangs, aborts or cannot be cancelled. */
    fun prepare(
        context: Context,
        files: UpscalePipelineFiles,
        shouldCancel: () -> Boolean,
        onStatus: (String) -> Unit,
        blockTimeoutMs: Long = 120_000,
        totalTimeoutMs: Long = 600_000,
    ) {
        if (files.npuBlocks.all { it.cache.isFile }) return
        val key = files.npuBlocks.joinToString("-") { it.sha256.take(8) }
        val failure = File(files.core.parentFile, "npu-failed-$key.txt")
        if (failure.isFile) throw IOException("previous NPU preparation failed: ${failure.readText()}")
        val completed = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        val block = AtomicInteger(0)
        val remote = AtomicReference<Messenger?>(null)
        val reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    NpuPreparationService.PROGRESS -> block.set(msg.arg1)
                    NpuPreparationService.DONE -> {
                        error.set(msg.data.getString("error"))
                        completed.countDown()
                    }
                }
            }
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try {
                    val worker = Messenger(binder)
                    remote.set(worker)
                    worker.send(Message.obtain(null, NpuPreparationService.START).apply { replyTo = reply })
                } catch (e: Exception) {
                    error.set(e.message ?: "could not start NPU compiler")
                    completed.countDown()
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                error.set("NPU compiler process exited")
                completed.countDown()
            }
            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
            override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
        }
        val intent = Intent(context, NpuPreparationService::class.java)
        var bound = false
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) throw IOException("could not bind NPU compiler")
            val start = SystemClock.elapsedRealtime()
            var blockStarted = start
            var previousBlock = -1
            var previousStatus = ""
            while (!completed.await(250, TimeUnit.MILLISECONDS)) {
                if (shouldCancel()) throw UpscaleCancelledException()
                val now = SystemClock.elapsedRealtime()
                val currentBlock = block.get()
                if (currentBlock != previousBlock) {
                    previousBlock = currentBlock
                    blockStarted = now
                }
                if (now - start > totalTimeoutMs || now - blockStarted > blockTimeoutMs) {
                    throw IOException("NPU compiler timed out on block ${currentBlock + 1}")
                }
                val status = "Preparing NPU ${currentBlock + 1}/${files.npuBlocks.size} · ${(now - start) / 1_000}s"
                if (status != previousStatus) {
                    onStatus(status)
                    previousStatus = status
                }
            }
            error.get()?.let { throw IOException(it) }
            check(files.npuBlocks.all { it.cache.isFile }) { "NPU preparation did not complete" }
        } catch (e: UpscaleCancelledException) {
            throw e
        } catch (e: Exception) {
            failure.writeText(e.message ?: "NPU preparation failed")
            throw e
        } finally {
            runCatching {
                remote.get()?.send(Message.obtain(null, NpuPreparationService.ABORT).apply { replyTo = reply })
            }
            if (bound) runCatching { context.unbindService(connection) }
        }
    }
}
