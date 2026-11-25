# Glassine: redesign phase 2

## Concept

**Glassine** is named after the translucent, faintly frosted paper of a record's inner
sleeve: you can see the colour of the record through it, softened. Petal's chrome is that
paper. Every cover is read for *where* its colours sit, and the page behind it is lit from
those same places, so a sleeve with a hot top-left corner lights the screen from the top
left. Frosted surfaces exist only where a sleeve or scrolling art lies underneath; plain
lists sit straight on the lit page. Type is set like the hand-cut divider cards in a record
shop: Bricolage Grotesque, very large and tight, narrowing on long names instead of
shrinking, and allowed to run over the edge of the art. Motion follows the music. The whole
palette turns over in 800 ms when the song changes, the light behind the cover follows the
deck's measured level, and the scrubber draws the song's waveform as you hear it. Nothing
moves on its own.

## Audit (before this phase)

Screens and what they render, from `app/src/main/kotlin/.../ui`:

| Screen | File | Notes |
|---|---|---|
| Root, tab bar, mini-player, overlays | `AppRoot.kt` | Three tabs (Listen now, Search, Queue). The mini-player is a solid `surface2` card. The tab bar is solid `surface1`. Player and Up Next are full-screen overlays with spring slides (damping .7–.85, which overshoots). |
| Now Playing | `NowPlayingScreen.kt` | Full-bleed cover with a vertical fade to a darkened swatch. Six transport icons in one row. AutoMix uses the sparkle icon. `ChromaticAura` draws a neon chromatic rim during a mix, on an infinite loop. Its own Palette-based extraction. |
| Home and Library | `HomeScreen.kt`, `LibraryScreen.kt` | One composable for both routes. Feature card with letter-spaced eyebrows ("JUMP BACK IN"). Filler copy ("All your music. One little world."). Source chips. Identical 150dp shelves. |
| Album, artist, playlist | `DetailScreens.kt`, `ArtHeader.kt` | Centred cover, centred title, two-stop vertical gradient from a darkened swatch. Small-caps meta line. A second Palette extraction. |
| Search | `SearchScreen.kt` | Outlined text field, source chips, shelves; the empty state is a glass card over a plain page. |
| Queue | `QueueScreen.kt` | Drag handle reorder, swipe to remove, "NOW PLAYING" card. |
| Settings, Downloads | `SettingsScreen.kt`, `DownloadsScreen.kt` | Material components on Petal tokens. |

Components: `springyClickable`, `GlassCard` (a solid surface, not glass), `PetalButton`,
`GlassIconButton`, `GlassTextButton`, `SourceChip`, `SourceDot`, `PetalSectionLabel`,
`PetalSeekBar`, `CardCaption`, `SourceBadge`, `TrackRow`, `CoverArt`, `rememberArt2x`,
`ArtHeaderScreen`, `PlayShufflePills`, `NumberedTrackRow`, `SectionLabel`,
`rememberPagePalette`, `MixWaveform`, `ChromaticAura`, `PetalBloom` (unused),
`AutoSizeText`, `TrackOverflowButton`, `AddToPlaylistDialog`, `PlaylistNameDialog`,
`CreatePlaylistButton`, `PlaylistActionsButton`, `SourceLogo`.

Tells found: the sparkle icon, the neon rim, infinite background loops, overshooting
springs, letter-spaced eyebrows, filler copy, two independent Palette extractions that
darken in RGB (muddy), glass named surfaces that never blur, the same 10dp radius on every
cover, centred detail headers, timid title sizes (20sp album titles), and stock Material
filled icons everywhere.

What the brief asks for that has no data in Petal today, so it is **not drawn** (no fake
content): synced lyrics, credits, monthly listeners, follower counts, per-track play counts,
upcoming shows, artist bios, daily mixes, charts, genre browse, listening-minute stats, a
user EQ, a sleep timer. Each needs a source API or a playback feature first.

## Tokens

All in `ui/theme`. Exact values.

### Colour roles

Static neutrals are unchanged from phase 1 (`Tokens.kt`). New dynamic roles come from the
artwork (`ArtTheme.kt`), extracted by k-means in OKLab on a 40×40 copy of the cover:

| Role | Rule |
|---|---|
| `dominant` | largest cluster |
| `vibrant` | highest chroma with 0.50 ≤ L ≤ 0.85 |
| `muted` | lowest chroma with 0.35 ≤ L ≤ 0.75 |
| `darkVibrant` | highest chroma with L < 0.50 |
| `lightMuted` | lowest chroma with L > 0.70 |
| `accent` | the most chromatic cluster at least 35° of hue from `dominant`, else `vibrant` |

A missing role is made from the nearest cluster by moving L and C in OKLCH, never by
falling back to grey.

Derived for an art surface (always night roles: art screens are lit, not papered):

| Derived | Value |
|---|---|
| `base` | dominant at L 0.17, C ≤ 0.06 |
| `glow` stops | each cluster at L clamped to 0.20–0.42, C ≤ 0.14, at its centroid |
| `ink` | L 0.98, C 0.012 at the dominant hue; checked ≥ 4.5:1 on the lightest glow stop |
| `text2` | L 0.82, raised until ≥ 4.5:1 on the lightest glow stop |
| `text3` | L 0.68, raised until ≥ 3:1 |
| `accentOnArt` | accent, L raised until ≥ 3:1 on the lightest stop (icons, large text) |
| `onAccent` | L 0.16 or L 0.98 at the accent hue, whichever passes 4.5:1 |
| `glassTint` | base, alpha per glass level |

No artwork: a deterministic palette from a 32-bit FNV-1a hash of `title + artist`. Hue is
`hash % 360`, then the stops are placed at ±28° and +150° from it, at fixed positions chosen
by hash bits.

Theme change: 800 ms, interpolated in OKLCH along the shorter hue arc.

### Glass levels

| Level | Use | Blur | Saturation | Tint alpha | Top highlight | Shadow |
|---|---|---|---|---|---|---|
| `Control` | buttons and chips on art | 24dp | 1.40 | 0.22 | 1dp, white 16 % | none |
| `Bar` | mini-player, tab bar, player deck | 32dp | 1.60 | 0.46 | 1dp, white 12 % | y 8dp, blur 24dp, black 28 % |
| `Sheet` | menus, sheets, toasts | 40dp | 1.80 | 0.62 | 1dp, white 10 % | y 16dp, blur 48dp, black 36 % |
| (solid) | Up Next drawer | none | | 0.94 | 1dp, white 10 % | as Sheet |

Grain on every glass surface and every art background: monochrome noise tile, 128 px,
3 % alpha. Reduced transparency (battery saver, or the *Reduce transparency* setting):
no blur, tint alpha 0.94.

### Type

Display: **Bricolage Grotesque** (OFL, variable `opsz` 12–96, `wdth` 75–100, `wght`
200–800). UI text: Instrument Sans. Data and time codes: IBM Plex Mono (always tabular).

Scale, ratio 1.25 from 14sp: 11 · 14 · 17.5 · 22 · 27 · 34 · 43 · 53 · 67 · 84.

| Style | Face | Size/line | Weight · width · tracking |
|---|---|---|---|
| `hero` | Bricolage | 67/60 | 800 · wdth 80 · −0.045em |
| `displayLarge` | Bricolage | 53/50 | 800 · 84 · −0.04em |
| `displayMedium` | Bricolage | 43/42 | 760 · 88 · −0.035em |
| `displaySmall` | Bricolage | 34/34 | 720 · 92 · −0.03em |
| `headlineMedium` | Bricolage | 27/28 | 700 · 96 · −0.02em |
| `titleLarge` | Bricolage | 22/24 | 650 · 100 · −0.01em |
| `titleMedium` | Instrument Sans | 17.5/22 | 600 |
| `bodyLarge` | Instrument Sans | 15/20 | 500 |
| `bodyMedium` | Instrument Sans | 14/20 | 400 |
| `bodySmall` | Instrument Sans | 11/14 | 500 |
| `labelLarge` | Instrument Sans | 14/18 | 600 |
| `labelSmall` | Plex Mono | 11/14 | 400 |

`opsz` follows the size. Long hero names narrow to `wdth` 75 before they shrink. The Now
Playing title's weight follows the deck level in 4 quantised steps between 760 and 820, off
under reduced motion.

### Radius

| Token | dp | Used for |
|---|---|---|
| `sleeve` | 3 | covers in lists and grids: records, not app icons |
| `sleeveHero` | 6 | Now Playing and album header covers |
| `tile` | 2 | genre/mood tiles (angled crop) |
| `control` | 14 | small square buttons, text field |
| `menu` | 18 | context menus, toasts |
| `sheet` | 28 | sheet top corners |
| `deck` | 28 | the player's floating control deck |
| `pill` | 50 % | mini-player, chips, filters, primary Play |
| circle | 50 % | artists, glass icon buttons |

### Spacing

4dp grid: `xxs` 2 · `xs` 4 · `s` 8 · `m` 12 · `l` 16 · `xl` 24 · `xxl` 32 · `xxxl` 48 ·
`huge` 64. Page gutter 20. Between sections 32.

### Motion

| Token | Value |
|---|---|
| `fast` | 120 ms |
| `base` | 220 ms |
| `slow` | 420 ms |
| `theme` | 800 ms |
| `Enter` | cubic-bezier(0.16, 1, 0.3, 1), smooth deceleration |
| `Press` | cubic-bezier(0.2, 0, 0, 1), snappy |
| `Exit` | cubic-bezier(0.4, 0, 1, 1) |
| `Drag` | spring, damping 1.0, stiffness 420: critically damped, never overshoots |
| `Settle` | spring, damping 1.0, stiffness 260 (sheets, the player) |

Press: scale 0.97 over `fast` with `Press`, shadow drops one level. No bounce or elastic
anywhere. When the system's *Remove animations* is on (animator scale 0), movement becomes
an opacity or colour change and audio reactivity is off.

### Z layers

| Layer | z |
|---|---|
| page | 0 |
| sticky header | 10 |
| tab bar, mini-player | 20 |
| player | 30 |
| Up Next drawer | 40 |
| menus, sheets | 50 |
| toasts | 60 |

## Where things live

| Piece | File |
|---|---|
| OKLab/OKLCH, contrast | `theme/Oklch.kt` |
| Extraction, roles, hash fallback | `theme/ArtTheme.kt` (pure Kotlin, `ArtThemeTest`) |
| Loading, cache, 800 ms cross-fade | `theme/ArtThemes.kt` |
| Motion tokens, reduced motion | `theme/Motion.kt` |
| Radius, spacing, z | `theme/Shape.kt` |
| Type | `theme/Type.kt`, `components/HeroText.kt` |
| Glass, grain, sleeve light, press | `components/Glassine.kt` |
| Live level, heard waveform | `components/LiveAudio.kt` (reads `PlaybackBus.levelProvider`) |
| Glyphs (play/pause morph, heart fill, equaliser) | `components/PetalIcons.kt` |
| Tab bar, mini-player, shared elements, toast | `components/Chrome.kt` |
| Row, swipe, context menu | `components/Components.kt`, `components/TrackMenu.kt` |
| Empty, error, skeleton states | `components/States.kt` |

The only change outside the UI layer: `PlaybackBus.levelProvider`, a read-only view of
the audible deck's existing `MixAudioMonitor` frame (`CrossfadePlayer.liveLevel`). It is
null on the Spotify pump, so audio-reactive details rest there.

## Self-audit against the anti-AI rules

Found and fixed:

- **Sparkle icon for AutoMix.** Replaced by the seam glyph (two crossing fade curves).
- **Neon chromatic rim during mixes, on an infinite loop** (`ChromaticAura`). Deleted. The mix is shown by the incoming cover rising through the outgoing one and the room's light cross-fading, both driven by mix progress.
- **Unused breathing bloom** (`PetalBloom`, an infinite decorative loop). Deleted.
- **Overshooting springs** (damping 0.6–0.85) on navigation, player, mini-player, AutoMix toggle. Replaced by `Motion` curves and critically damped springs.
- **Letter-spaced uppercase eyebrows** ("JUMP BACK IN", "NOW PLAYING", "AUTOMIX CONTINUING WITH"). Now sentence case in the UI face.
- **Filler copy** ("All your music. One little world.", "Find your next favourite.", "A little more of what you love."). Replaced with sentences that say something real: song counts, what will happen.
- **"Glass" that wasn't glass.** `GlassCard` and the glass roles were solid fills. Real backdrop blur now exists only where something is underneath: chrome over the page, the deck over the player's room, the search header over results, the drawer and menu over the app. `GlassCard` is documented as a plain surface.
- **One radius everywhere** (10–18dp). Now a system: 3dp sleeves, 6dp hero sleeves, 2dp tiles, pills for floating things and filters, circles for artists.
- **Centred detail headers, timid 20sp titles.** Left-aligned, 53–67sp condensed titles that overlap the cover.
- **Two-stop darkened-RGB gradients.** Replaced by positioned OKLCH lights with grain.
- **Identical 150dp shelves.** Sizes now vary by meaning: 196 → 132 in Recently played, 176 albums, 136 playlists, 104 round artists, 2-up grid.
- **Stock filled Material icons for the key controls.** Play/pause, like, shuffle, repeat, skip, queue, share, output and AutoMix are drawn as one stroke family.

Remaining, knowingly:

- Secondary icons (home, search, settings, download, sort) are Material Rounded, not redrawn.
- Downloads and most of Settings keep their Material layout, restyled through the tokens rather than redesigned.
- Not drawn, because Petal has no data for it: monthly listeners, followers, play counts, shows, bios, related artists, daily mixes, charts, listening stats, a user EQ, playlist reorder.

## Added after the first pass

- **Lyrics** (player → lyrics glyph). The source's own first (TIDAL `tracks/{id}/lyrics`), then LRCLIB (`data/Lyrics.kt`), fetched only when the panel opens; *Look up lyrics on LRCLIB* in Settings turns that off. Timed lines follow the playhead, tap a line to seek. `LyricsParserTest`.
- **Credits** (player → More → Credits). TIDAL `tracks/{id}/contributors`, or the composer tag for device files.
- **Explicit** badge from TIDAL `explicit`, Spotify `explicit`/`contentRating`, YT Music's explicit badge.
- **Play next / Add to queue**: `PlaybackClient.playNext`/`addToQueue`; rows swipe left to play next; both in the long-press menu.
- **Sleep timer** (`playback/SleepTimer.kt`): 15–90 min or end of song, pauses through `PlaybackBus.pauseAction`; a countdown sits by the player's close button.
- **Artist names** link everywhere (`ArtistLink`): rows, player, album byline, queue, menu, credits. Tracks now carry the source's artist id; a source without artist pages (YT Music, offline) opens a search for the artist instead. TIDAL artist pages work (`artistTopTracks` was missing).

## Emulator walkthrough (2026-11-24)

Scripted runs in `design/review/glassine-walkthrough/` (`boot.sh`, then `python3 walk.py passN.py`; screenshots and `sheet.py` contact sheets). Fixed from what they showed:

- Sleeve light drew outside its box (unclipped radial glows) and smeared album/artist headers over their track lists.
- Home's top light stayed fixed while scrolling, leaving blobs behind the library; it now scrolls away with the greeting and feature tile.
- Detail headers: two stacked height modifiers left no room for the back button; the cover now fades under the title and fades out on scroll; the artist portrait darkens earlier so text printed on a cover can't fight the name.
- Rows with a hidden artist (album tracks) left the source badge alone on a second line.
- Tab-bar mark moved under the labels (it doubled the mini-player's progress line).
- Back inside the player closes its sheet or lyrics first (`PlayerBack`), not the whole player.
- Status-bar protection on Home and Settings; denser search header; Up Next drawer is solid.
- Performance: the player's room is a 24 px copy of the cover, not a 72dp blur of the full one; glass blurs a quarter-size copy and caches its effect; the mini-player's progress line updates at 4 Hz, not every frame. The first two were taking the emulator's GPU host down (qemu core dumps).

Still seen on the emulator: its software GPU occasionally dies after a long run (frames there take 1–2 s each). There is no app error in the logs. Watch for jank or GPU resets on a real device.

## Battery pass (2026-11-24)

Measured on the review emulator with `design/review/battery/measure.sh` (frames drawn while nobody touches the screen; app CPU per minute) and `threads.sh` (per-thread CPU). The emulator caps at about 5 fps, so its frame counts understate the "before" figures: those loops asked for every vsync, which is 60–120 a second on a phone.

| Idle state | Before | After |
|---|---|---|
| Home, paused | 15 frames / 30 s | 0 |
| Home, playing | every vsync (152 / 30 s at the emulator's cap) | 2 a second (mini-player line) |
| Player, playing | every vsync | 4 a second (playhead) + 30 Hz glow, off on battery saver |
| Playback thread, steady | 6.0 s CPU / 30 s | 2.9 s / 30 s |
| Paused engine poll | every 2 s | every 10 s (play/pause/seek wake it at once) |
| Session save | whole queue to JSON every 5 s | queue only when it changes; playhead (a few bytes, own file) every 15 s and on pause |

What changed:

- `PlaybackBus.publish` drops updates where only the countdowns moved; the UI never reads them, and every update recomposed the app.
- Audio-reactive drawing samples on a timer (15 Hz for row equalisers, 30 Hz for the player's glow), the row sampler only while an equaliser is on screen (`WatchLevel`), and none of it on battery saver (`LocalPowerSave`). The heard-waveform recorder runs at 10 Hz.
- The player's playhead is read through lambdas, so only the scrubber and lyrics recompose as it moves.
- Marquees scroll three times per title, then rest.
- ExoPlayer dynamic scheduling (`experimentalSetDynamicSchedulingEnabled`): the playback thread sleeps until the sink needs data. Same decode, same processing, same timing. Crossfades and prelistens ran with no underruns.
- 2x cover upscaling waits while battery saver is on.

Left alone on purpose (AutoMix behaviour):

- The deck chain stays warm at unity: about 0.4 s CPU per minute of audio on a desktop core (`probe/DspCostProbe`).
- The silent 3x prelisten of the next song runs once per song, about 20 s of decoding, only with AutoMix on.
