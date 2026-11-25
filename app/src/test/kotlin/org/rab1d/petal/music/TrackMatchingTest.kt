/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import org.junit.Assert.*
import org.junit.Test
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.model.Track
import org.rab1d.petal.music.model.equivalentTrack

class TrackMatchingTest {
    private val original = Track("1", Source.TIDAL, "Déjà Vu", "Beyoncé", durationMs = 240_000)

    @Test fun matchesRecordingDespitePunctuationAndDiacritics() {
        val match = original.copy(id = "2", source = Source.SPOTIFY, title = "Deja-Vu", artist = "Beyonce", durationMs = 242_000)
        assertEquals(match, equivalentTrack(original, listOf(match)))
    }

    @Test fun rejectsCoversRemixesAndDifferentLengthVersions() {
        val candidates = listOf(original.copy(artist = "Cover Band"),
            original.copy(title = "Déjà Vu (Remix)"), original.copy(durationMs = 310_000))
        assertNull(equivalentTrack(original, candidates))
    }

    @Test fun isrcIdentifiesRecordingWithoutDisplayMetadataAgreement() {
        val match = original.copy(id = "2", title = "Different display name", isrc = "US1234567890")
        assertEquals(match, equivalentTrack(original.copy(isrc = "us1234567890"), listOf(match)))
    }

    @Test fun featureCreditsInDifferentFormatsStillMatch() {
        val spotify = Track("1", Source.SPOTIFY,
            "Get Lucky (feat. Pharrell Williams and Nile Rodgers)", "Daft Punk", durationMs = 369_000)
        val ytm = spotify.copy(id = "2", source = Source.YT_MUSIC,
            "Get Lucky (feat. Pharrell Williams, Nile Rodgers)", "Daft Punk - Topic", durationMs = 371_000)
        assertEquals(ytm, equivalentTrack(spotify, listOf(ytm)))
    }

    @Test fun differentCoreTitleNeverMatchesEvenWithSameArtist() {
        val cover = original.copy(id = "2", title = "Déjà Vu Live")
        assertNull(equivalentTrack(original, listOf(cover)))
    }

    @Test fun masteringSuffixesDoNotBlockAMatch() {
        val remaster = original.copy(id = "2", source = Source.SPOTIFY, title = "Déjà Vu - 2012 Remaster")
        val plain = original.copy(id = "3", source = Source.YT_MUSIC, title = "Déjà Vu")
        assertEquals(plain, equivalentTrack(remaster, listOf(plain)))
    }
}
