/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.rab1d.petal.music.sources.ytmusic.OkHttpDownloader
import org.rab1d.petal.music.sources.ytmusic.hasYouTubeSession
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

class YouTubeDownloaderTest {
    private var sent: okhttp3.Request? = null

    private fun downloader(code: Int = 200): OkHttpDownloader = OkHttpDownloader(
        cookieProvider = { "SAPISID=account-secret; SOCS=CAI" },
        client = OkHttpClient.Builder().addInterceptor { chain ->
            sent = chain.request()
            okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").body("{\n  \"value\": \"two words\"\n}".toResponseBody()).build()
        }.build(),
    )

    @Test fun `extractor requests retain account and request cookies`() {
        val response = downloader().execute(Request.newBuilder().get("https://www.youtube.com/watch?v=test")
            .setHeader("cookie", "PREF=language; SOCS=CAE").build())
        val header = sent!!.header("Cookie")!!
        assertTrue(header.contains("SAPISID=account-secret"))
        assertTrue(header.contains("PREF=language"))
        assertTrue(header.contains("SOCS=CAI"))
        assertFalse(header.contains("SOCS=CAE"))
        assertEquals(1, header.split(';').count { it.trim().startsWith("SOCS=") })
        assertEquals("{\n  \"value\": \"two words\"\n}", response.responseBody())
        assertEquals("https://www.youtube.com/watch?v=test", response.latestUrl())
    }

    @Test fun `account cookies stay off native API and unrelated hosts`() {
        val downloader = downloader()
        listOf("https://youtubei.googleapis.com/youtubei/v1/player", "https://notyoutube.com/",
            "https://www.youtube.com.example.org/").forEach { url ->
            downloader.execute(Request.newBuilder().get(url).build())
            assertNull(sent!!.header("Cookie"))
        }
    }

    @Test fun `consent and partial SID cookies do not constitute login`() {
        assertFalse(hasYouTubeSession("SOCS=CAI; SID=partial"))
        assertFalse(hasYouTubeSession("SAPISID="))
        assertTrue(hasYouTubeSession("__Secure-3PAPISID=account"))
    }

    @Test(expected = ReCaptchaException::class)
    fun `rate limit is surfaced as an interactive challenge`() {
        downloader(429).execute(Request.newBuilder().get("https://www.youtube.com/").build())
    }
}
