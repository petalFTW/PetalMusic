/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.sources.ytmusic

/** Later headers replace earlier values; cookie names are case-sensitive. */
internal fun mergeYouTubeCookies(vararg headers: String?): String = buildMap<String, String> {
    headers.forEach { header ->
        header.orEmpty().split(';').forEach { item ->
            val pair = item.trim()
            val name = pair.substringBefore('=')
            if (name.isNotEmpty() && '=' in pair) put(name, pair)
        }
    }
}.values.joinToString("; ")

internal fun hasYouTubeSession(header: String?): Boolean = header.orEmpty().split(';')
    .any { it.trim().substringBefore('=') in setOf("SAPISID", "__Secure-3PAPISID") &&
        it.substringAfter('=', "").isNotBlank() }
