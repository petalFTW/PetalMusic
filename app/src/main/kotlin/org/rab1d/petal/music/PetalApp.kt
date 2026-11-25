/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache

class PetalApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // The compiler worker must not initialize playback, downloads or account
        // clients. A native compiler crash is confined to this secondary process.
        if (getProcessName().endsWith(":npu_prepare")) return
        container = AppContainer(this)
    }

    // covers need to survive offline, coil default cache is tiny and gets evicted
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(object : coil.map.Mapper<String, android.net.Uri> {
                    override fun map(data: String, options: coil.request.Options): android.net.Uri? {
                        // Every cover in the app uses the 2x copy once it exists.
                        org.rab1d.petal.music.data.ArtUpscaler.cached(data)?.let { return it }
                        val cover = org.rab1d.petal.music.model.highQualityArtwork(data)
                        return if (cover != data) android.net.Uri.parse(cover) else null
                    }
                })
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("cover_cache"))
                    .maxSizeBytes(160L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
