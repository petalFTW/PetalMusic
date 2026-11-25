/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.sources

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Serves petal://<key> uris through [PetalStreamResolver] with OkHttp.
 * Handles range requests and Deezer-style Blowfish chunk decryption.
 */
@UnstableApi
class PetalDataSource : BaseDataSource(true) {

    private val client: OkHttpClient = OkHttpClient.Builder().build()
    private var stream: InputStream? = null
    private var response: okhttp3.Response? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var streamPos: Long = 0
    private var bf: BlowfishState? = null
    private var mpdBytes: ByteArray? = null
    private var mpdPos = 0
    private var petalUri: Uri? = null

    private class BlowfishState(
        val key: ByteArray,
        val iv: ByteArray,
        val encryptedChunks: Int,
        val chunkSize: Int,
    ) {
        val cipher: Cipher = Cipher.getInstance("Blowfish/CBC/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "Blowfish"), IvParameterSpec(iv))
        }
        var plainChunk: ByteArray? = null
        var plainChunkIndex: Long = -1
        var plainPos: Int = 0
        var plainFilled: Int = 0
        val cipherLimit: Long get() = encryptedChunks.toLong() * chunkSize
    }

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val key = dataSpec.uri.schemeSpecificPart?.trimStart('/')
            ?: throw HttpDataSource.InvalidResponseCodeException(
                400, "bad uri", null, emptyMap(), dataSpec, ByteArray(0),
            )
        var handle = PetalStreamResolver.resolve(key)
        android.util.Log.i("PetalDS", "open key=$key handle=${handle?.let { it::class.simpleName }} pos=${dataSpec.position} len=${dataSpec.length}")
        if (handle == null && key.matches(Regex("^[A-Za-z0-9.-]+\\.[A-Za-z]{2,}/.*"))) {
            // DASH segment urls resolved against the petal:// manifest base
            // can lose their scheme — restore https.
            handle = StreamHandle.Url("https://$key")
        }
        if (handle == null) {
            android.util.Log.w("PetalDS", "resolver MISS for $key")
            throw HttpDataSource.InvalidResponseCodeException(
                404, "unknown stream", null, emptyMap(), dataSpec, ByteArray(0),
            )
        }
        petalUri = dataSpec.uri
        if (handle is StreamHandle.Mpd) {
            android.util.Log.i("PetalDS", "MPD head=${handle.xml.take(320)}")
            val bytes = handle.xml.toByteArray()
            mpdBytes = bytes
            mpdPos = dataSpec.position.toInt().coerceIn(0, bytes.size)
            val total = (bytes.size - mpdPos).toLong()
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                minOf(total, dataSpec.length)
            } else total
            streamPos = dataSpec.position
            transferStarted(dataSpec)
            return bytesRemaining
        }
        val (url, headers) = when (handle) {
            is StreamHandle.Url -> handle.url to handle.headers
            is StreamHandle.Blowfish -> handle.url to handle.headers
            is StreamHandle.Mpd -> throw IllegalStateException("unreachable")
        }
        android.util.Log.i("PetalDS", "http fetch $url")

        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
            val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else (dataSpec.position + dataSpec.length - 1).toString()
            requestBuilder.header("Range", "bytes=${dataSpec.position}-$end")
        }

        val response = client.newCall(requestBuilder.build()).execute()
        this.response = response
        if (response.code != 200 && response.code != 206) {
            throw HttpDataSource.InvalidResponseCodeException(
                response.code, response.message, null, response.headers.toMultimap(), dataSpec, ByteArray(0),
            )
        }
        stream = response.body?.byteStream()
        if (handle is StreamHandle.Blowfish) {
            bf = BlowfishState(handle.key, handle.iv, handle.encryptedChunks, handle.chunkSize)
        }

        val contentLength = response.body?.contentLength() ?: -1L
        val total: Long = when {
            response.code == 206 -> contentLength.coerceAtLeast(0)
            else -> skip(dataSpec.position) // range ignored: skip manually
        }
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            total >= 0 -> total
            else -> C.LENGTH_UNSET.toLong()
        }
        streamPos = dataSpec.position
        transferStarted(dataSpec)
        return bytesRemaining
    }

    /** Skip [n] bytes; returns the remaining length if known, else -1. */
    private fun skip(n: Long): Long {
        var left = n
        val scratch = ByteArray(16384)
        while (left > 0) {
            val got = rawRead(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (got < 0) return -1
            left -= got
        }
        val contentLength = response?.body?.contentLength() ?: -1L
        return if (contentLength >= 0) contentLength - n else -1L
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        mpdBytes?.let { b ->
            if (mpdPos >= b.size || bytesRemaining == 0L) {
                bytesRemaining = 0
                return C.RESULT_END_OF_INPUT
            }
            val cap = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length, bytesRemaining.toInt())
            val n = minOf(b.size - mpdPos, cap)
            System.arraycopy(b, mpdPos, buffer, offset, n)
            mpdPos += n
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
            bytesTransferred(n)
            return n
        }
        val read = rawRead(buffer, offset, length)
        if (read == -1) {
            bytesRemaining = 0
            return C.RESULT_END_OF_INPUT
        }
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    /** Cipher-aware read used both for serving and for skipping. */
    private fun rawRead(buffer: ByteArray, offset: Int, length: Int): Int {
        val input = stream ?: return -1
        val b = bf
        if (b == null || streamPos >= b.cipherLimit) {
            val read = input.read(buffer, offset, length)
            if (read > 0) streamPos += read
            return read
        }
        return readEncrypted(b, input, buffer, offset, length)
    }

    private fun readEncrypted(
        b: BlowfishState,
        input: InputStream,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val chunkIndex = streamPos / b.chunkSize
        if (b.plainChunk == null || chunkIndex != b.plainChunkIndex) {
            val encrypted = ByteArray(b.chunkSize)
            var filled = 0
            while (filled < b.chunkSize) {
                val r = input.read(encrypted, filled, b.chunkSize - filled)
                if (r < 0) break
                filled += r
            }
            if (filled == 0) return -1
            // Pad to a cipher block multiple; served bytes are capped at `filled`.
            val block = if (filled == b.chunkSize) encrypted else encrypted.copyOf(b.chunkSize)
            val plain = b.cipher.doFinal(block)
            b.plainChunk = plain
            b.plainChunkIndex = chunkIndex
            b.plainPos = 0
            b.plainFilled = filled
        }
        val remainingInChunk = b.plainFilled - b.plainPos
        val toCopy = minOf(remainingInChunk, length, (b.cipherLimit - streamPos).toInt().coerceAtLeast(1))
        System.arraycopy(b.plainChunk, b.plainPos, buffer, offset, toCopy)
        b.plainPos += toCopy
        streamPos += toCopy
        if (b.plainPos >= b.plainFilled) b.plainChunk = null
        return toCopy
    }

    override fun close() {
        runCatching { stream?.close() }
        runCatching { response?.close() }
        stream = null
        response = null
        bf = null
        mpdBytes = null
        bytesRemaining = C.LENGTH_UNSET.toLong()
    }

    override fun getUri(): Uri? = petalUri
        ?: response?.request?.url?.let { Uri.parse(it.toString()) }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = PetalDataSource()
    }
}
