/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.model

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Match recordings between catalogues without silently substituting a remix/cover. */
fun equivalentTrack(original: Track, candidates: List<Track>, durationToleranceMs: Long = 5_000): Track? {
    if (!original.isrc.isNullOrBlank()) {
        candidates.firstOrNull { it.isrc?.equals(original.isrc, ignoreCase = true) == true }?.let { return it }
    }
    fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    // Catalogues disagree on feature credits ("feat. A and B" vs "feat A, B")
    // and on mastering suffixes ("- 2012 Remaster"); compare on the core title
    // with credit/version segments stripped. Live/remix stay distinct on purpose.
    fun coreTitle(text: String) = normalized(text)
        .replace(Regex("\\b(feat|ft|featuring|with)\\b.*$"), "")
        .replace(Regex("\\b(19|20)\\d{2}\\b|\\bremaster(ed)?\\b|\\bmono\\b|\\bstereo\\b|" +
            "\\bdeluxe\\b|\\bexpanded\\b|\\bedition\\b|\\banniversary\\b|\\bversion\\b|" +
            "\\bsingle\\b|\\bedit\\b"), "")
        .trim()
    val title = normalized(original.title)
    val core = coreTitle(original.title)
    val artist = normalized(original.artist)
    if (core.isBlank() || artist.isBlank()) return null
    fun titleMatches(candidate: Track): Boolean =
        normalized(candidate.title) == title ||
            (core.isNotBlank() && coreTitle(candidate.title) == core)
    fun artistMatches(candidate: Track): Boolean {
        val other = normalized(candidate.artist)
        return artist.isBlank() || other.let { it == artist || it.contains(artist) || artist.contains(it) }
    }
    return candidates.filter {
        titleMatches(it) && artistMatches(it) &&
            (original.durationMs <= 0 || it.durationMs <= 0 || abs(it.durationMs - original.durationMs) <= durationToleranceMs)
    }.minByOrNull { if (it.durationMs > 0 && original.durationMs > 0) abs(it.durationMs - original.durationMs) else Long.MAX_VALUE }
}
