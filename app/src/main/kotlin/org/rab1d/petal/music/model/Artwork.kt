/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.model

/** YouTube Music returns small, size-qualified covers even for the player view. */
fun highQualityArtwork(uri: String): String {
    if (!uri.startsWith("https://lh3.googleusercontent.com/") &&
        !uri.startsWith("https://yt3.googleusercontent.com/")) return uri
    return uri.replace(Regex("=w\\d+-h\\d+"), "=w1200-h1200")
        .replace(Regex("=s\\d+"), "=s1200")
}
