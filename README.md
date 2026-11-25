# Petal Music

The music player for petalOS. It plays files on the phone and streams from TIDAL, Spotify,
YT Music and Deezer, all in one queue. Because it owns that queue, it can mix any song into
any other, whatever the source.

There are no releases here. Build it yourself (see [BUILD.md](BUILD.md)).

## What it does

- **One library.** Songs on the phone (MediaStore), and your saved music from each service
  you sign in to, side by side.
- **Sources.**
  - TIDAL signs in by device pairing: the app shows a code and you approve it at
    link.tidal.com.
  - Spotify plays through librespot.
  - YT Music plays through NewPipeExtractor.
  - Deezer signs in with an ARL cookie.
  - All of these are unofficial clients. Use them with accounts you own.
- **Downloads.** TIDAL songs can be saved for offline play. The offline library is what the
  app shows when there is no connection.
- **AutoMix.** Two engines, chosen in Settings:
  - *Smooth blend* (v1, the default): an EQ crossfade that trades bass, mids and highs
    between the two songs.
  - *DJ mix* (v2): beat-matched moves chosen from the music itself. It can bring in a new
    beat under the vocal, run the vocal a cappella over the next song's intro, or loop a
    bar and ride its tempo to the next song's. It can also hold a song at the mix tempo
    and glide it home after.
  - Stem moves split vocals and drums (HPSS, no model needed) for local and downloaded
    files.
  - A pair v2 has no move for gets v1's blend.
- **BridgeFX.** On petalOS the app is platform-signed and can hand transitions to the
  system BridgeFX audio effect. Elsewhere the same mixing runs inside the app.
- **Desktop.** A Windows and Linux build lives in `desktop/`.

The design of AutoMix is in [docs/automix-design.md](docs/automix-design.md) and the
interface in [docs/ui-redesign.md](docs/ui-redesign.md).

## Flavours

| Flavour | For | Extras |
|---|---|---|
| `rom` | petalOS, platform-signed | BridgeFX HAL, island hooks, on-device models through ONNX Runtime + QNN |
| `fdroid` | Any Android 12+ phone | None of the above. AutoMix runs on the app's own DSP |

## Design rules

These came out of the interface redesign (Glassine). They apply to the app, and to anything
written about it, this file included.

1. **Nothing moves on its own.** Motion answers the music or a touch. No idle loops, no
   breathing glows, no shimmer for its own sake.
2. **No overshoot.** Springs are critically damped. Things arrive and stop.
3. **No sparkle icon.** A feature gets a glyph that shows what it does: AutoMix is two
   crossing fade curves, not a "magic" star.
4. **Sentence case, no eyebrows.** No letter-spaced all-caps labels such as "JUMP BACK IN".
5. **No filler copy.** Every line says something true and specific: a song count, what will
   happen when you tap. Not "All your music. One little world."
6. **No fake content.** If Petal has no data for something (listener counts, bios, charts,
   play counts), it isn't drawn. No placeholders made to look real.
7. **Glass only over something.** Blur and frosting appear only where art or content
   scrolls underneath. A solid surface is called solid.
8. **Colour comes from the art.** Covers are read in OKLCH for where their colours sit, and
   the page is lit from those places. No two-stop gradients from a darkened swatch.
9. **Size means something.** Shelves, radii and type sizes vary by what they hold, not one
   value everywhere. Headers are left-aligned and large.
10. **One type family.** Bricolage Grotesque ExtraBold throughout. Figures are tabular where
    numbers change.
11. **One icon stroke.** The controls people use most are drawn as one family, not stock
    filled icons.

## Building

[BUILD.md](BUILD.md) covers the SDK, both flavours, tests, the AutoMix bench and the
platform-signed petalOS build.

## Licence

Apache 2.0. The bundled fonts are under the SIL Open Font License; their licences are in
`app/src/main/assets/licenses/`.
