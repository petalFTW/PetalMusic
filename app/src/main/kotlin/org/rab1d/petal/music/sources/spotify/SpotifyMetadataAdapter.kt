/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.sources.spotify

import com.spotify.extendedmetadata.ExtendedMetadata
import com.spotify.extendedmetadata.ExtensionKindOuterClass
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.dealer.ApiClient
import xyz.gianlu.librespot.metadata.TrackId
import java.io.IOException

/** Adapt the bundled feeder's legacy track request to Spotify's current wire API. */
internal class SpotifyMetadataAdapter : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val prefix = "/metadata/4/track/"
        if (original.method != "GET" || !original.url.encodedPath.startsWith(prefix)) {
            return chain.proceed(original)
        }
        val id = original.url.encodedPath.removePrefix(prefix)
        val request = ExtendedMetadata.BatchedEntityRequest.newBuilder()
            .addEntityRequest(ExtendedMetadata.EntityRequest.newBuilder()
                .setEntityUri(TrackId.fromHex(id).toSpotifyUri())
                .addQuery(ExtendedMetadata.ExtensionQuery.newBuilder()
                    .setExtensionKind(ExtensionKindOuterClass.ExtensionKind.TRACK_V4)))
            .build()
        val response = chain.proceed(original.newBuilder()
            .url(original.url.newBuilder().encodedPath("/extended-metadata/v0/extended-metadata").query(null).build())
            .post(ApiClient.protoBody(request)).build())
        if (!response.isSuccessful) return response
        val bytes = response.use {
            val body = it.body ?: throw IOException("Spotify returned no audio metadata")
            val batch = ExtendedMetadata.BatchedExtensionResponse.parseFrom(body.byteStream())
            if (batch.extendedMetadataCount == 0 || batch.getExtendedMetadata(0).extensionDataCount == 0) {
                throw IOException("Spotify returned no track metadata extension")
            }
            val data = batch.getExtendedMetadata(0).getExtensionData(0)
            if (data.header.statusCode != 200) throw IOException("Spotify track metadata: ${data.header.statusCode}")
            data.extensionData.value.toByteArray()
        }
        // Keep the binary Track contract expected by the existing feeder.
        return response.newBuilder().request(original).removeHeader("Content-Length")
            .header("Content-Type", "application/x-protobuf")
            .body(bytes.toResponseBody("application/x-protobuf".toMediaType())).build()
    }

    companion object {
        fun install(session: Session) {
            // Session has a public client getter but no setter in this version.
            // All librespot classes/fields are kept by the release shrinker.
            val field = Session::class.java.getDeclaredField("client").apply { isAccessible = true }
            val client = field.get(session) as OkHttpClient
            if (client.interceptors.none { it is SpotifyMetadataAdapter }) {
                field.set(session, client.newBuilder().addInterceptor(SpotifyMetadataAdapter()).build())
            }
        }
    }
}
