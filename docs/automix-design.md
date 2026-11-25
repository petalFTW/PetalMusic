# Automix: musical handoffs

## Research

Spotify documents beat-aware transition editing, separate EQ/volume/effect controls,
and transition presets. These are public product capabilities, not a published
implementation of its proprietary automatic planner:
https://support.spotify.com/cg-en/article/mixed-playlists/
https://newsroom.spotify.com/2025-08-19/mix-your-favorite-playlists-seamlessly-by-adding-your-own-transitions/

Algoriddim documents distinct EQ, filter, echo and other automix transitions,
beatmatching choices and cue markers:
https://help.algoriddim.com/user-manual/djay-android/settings/automix

Vande Veire and De Bie's open automatic-DJ research treats beat/downbeat tracking,
structural cue selection, tempo matching and frequency-dependent crossfades as
separate tasks. Its evaluation concerns Drum and Bass; that evidence should not
be generalized into a claim of reliable phrasing for arbitrary music:
https://link.springer.com/article/10.1186/s13636-018-0134-8

## Implementation

The dual-ExoPlayer path averages dry measurements from both decks before selecting
an arrangement. `PartExchangePlan` jointly scores candidate incoming lead and
outgoing carrier groups using their energy shares, transients and harmonic
compatibility. Sub and bass remain one group to preserve kick coherence.

The selected incoming lead enters on a short ramp and then holds. Another group
trades later, leaving a sustained combination of parts from different tracks.
Where the outgoing lead group has enough energy and rhythmic activity, it can
return briefly as an answer. Sustained incompatible mids never receive this
rhythmic return. The outgoing carrier stays present until the closing exchange;
all bands reach incoming unity at the actual endpoint.

Measured spectral distance and energy trends set exchange timing and ramp widths.
A known tempo quantizes eligible cues. This is deterministic: identical audio and
hints produce identical decisions; changing either spectrum can change the roles,
cues and return events. These are live spectral arrangements, not random presets
or full-song phrase analysis. Unknown audio is explicitly labelled as a gentle
fallback blend. Quiet incoming passages retain the outgoing before final release.

`DjCrossover` replaces the old 6 dB/octave low-pass differences with three
phase-compensated 24 dB/octave Linkwitz–Riley splits. The summed response is flat
in magnitude, with all-pass phase rather than a sample-identical bypass. Both
decks use the same filters throughout playback, avoiding a phase switch at entry.
Biquad equations follow the W3C/RBJ reference:
https://www.w3.org/TR/audio-eq-cookbook/

`PetalParts` diagnostics report actual band commands, measured levels and the
selected arrangement each second. The UI retains their chronological history.

Preparation completes before scheduling the incoming start. Reliable beat phases
cue the incoming within its first beat and schedule the outgoing against its
actual media position. The outgoing tempo is matched before overlap and held;
the incoming media clock advances the EQ envelope. Half/double-time estimates
retain their residual tempo correction. Fractional beat periods are not rounded
before projecting a grid over a long track. HAL elapsed-beat measurements are
converted into track-relative phase origins.

The planner budgets complete four-beat bars at incoming tempo when space permits.
A beat phase alone cannot identify a downbeat, meter, phrase boundary or suitable
instrumental cue. The existing structure/outro detection remains separate.

## Scope and validation

These changes concern the in-app dual-player path. Spotify's librespot mixer and
cross-source/HAL fallback renderers have separate implementations and do not gain
this frequency arrangement automatically.

Unit tests cover pair-dependent role selection and timing, held parts near the
endpoint, evidence-gated rhythmic returns, valid events across diverse spectra,
quiet-intro protection, bounded commands, tempo ratios and fractional beat grids.
A PCM integration test renders both decks and verifies outgoing bass and incoming
mids coexist while their conflicting tones are rejected. Filter tests verify flat
unity magnitude across sample rates, band rejection, reset and complete silence.

Device listening remains necessary. Independent ExoPlayer/AudioTrack starts are
not sample-synchronous; startup/output latency and buffering can still offset
beats. This is not a stem separator or a complete offline phrase-analysis system.
Useful listening cases: bass-heavy dance pairs, competing vocal mids, quiet intros,
half-time pairs, unrelated tempos, pause/resume, buffering and manual skips.

## Tempo and EQ regression repair

The incoming deck is the native-tempo master for the complete overlap. Removed
periodic incoming speed nudges and the fallback boundary speed glide (which was
changing the already-selected incoming track). The outgoing receives one tempo
setting before overlap; progress follows incoming media time, including stalls.

A processor flush caused by a tempo change now preserves crossover state, gain
ramps, dry measurements and their media clock. Explicit seeks/item replacements
still invalidate measurements and reset DSP. Regression coverage compares PCM
across such a flush against uninterrupted processing late in a track.

Unmatched tempos receive up to eight seconds for spectral part exchange without
inventing beat alignment or rhythmic returns. Band exchanges use 150–400 ms
ramps subject to the overlap budget. Distant known Camelot families discourage
retaining outgoing mids and favour an incoming melodic lead; absent keys make no
compatibility claim. This is a key-family hint, not chord tracking or separation
of vocals and instruments. The visualiser separates actual gain histories into
four labelled lanes and uses the incoming tempo for its bar grid.


## Incoming beat-group endpoint

The renderer re-evaluates the overlap budget after preparation and outgoing beat
alignment. It converts the actual remaining outgoing media time at its matched
speed to wall time, reserves 250 ms for startup/control latency, and selects a
complete eight-beat group on the incoming cue grid (four if eight will not fit).
It no longer clips the planned whole-bar overlap to the outgoing's last beat.
Fractional periods retain millisecond rounding tolerance, and a sampled incoming
playhead offset does not shift the cue-relative endpoint. Short tails without a
complete group and unknown tempos retain a bounded emergency fade.

Tests cover preparation truncating eight beats to seven, long truncated phrases,
fractional tempos, cue offsets, outgoing speed conversion and short-tail limits.
These are groups relative to the incoming cue, not newly detected musical
downbeats. Meter/phrase detection and sample-synchronous dual-player output remain
outside this timing repair; unexpected stalls can still exhaust an outgoing tail.

## Outgoing release before the final bar

The closing envelope now supersedes the old final-half-beat release. For mixes
of at least four bars, every remaining outgoing band fades over one incoming bar,
from N−2 to N−1 elapsed bars; the last bar is incoming only. Thus a 16-bar window
releases across 14–15, an eight-bar window across 6–7, and a four-bar window across
2–3. Short beat-group mixes use one beat; unknown tempos use a bounded fractional
release without claiming bar alignment. Runtime duration is capped at 16 bars.

All measured, missing-analysis and quiet-intro paths share this release. Actual
band levels freeze at release entry and move smoothly to outgoing zero/incoming
unity, preventing changing spectral measurements or a rhythmic return from
reviving an outgoing part. Arrangement events fit before the closing window.
Tests cover 4/8/16-bar deadlines, monotonic outgoing release under changing audio,
missing measurements, unity through the last bar, and the sixteen-bar cap.

## Track analysis (v1 and v2)

The petalOS HAL's analysis proved unusable in testing: within one song it reported
69 → 103 → 139 → 147 → 72 BPM at "confidence 1.0", and its key changed every poll.
It no longer writes grids, phases or keys; it only seeds a tempo hint for a record
nothing else knows, and its downbeat is used only when nothing measured one.

`TrackAnalyzer` analyses a whole record (`BandEnvelope`: full, low, mid, high and a
1–4 kHz vocal-presence band at ~5.8 ms hops, on the deck's exact media clock):

- onset strength from log-compressed band flux (kick, snare body, crack/hats);
- tempo from a comb-filtered autocorrelation with a log-normal prior around 95 BPM,
  so half/double-time ambiguity resolves the way hip-hop, R&B and pop count;
- Ellis dynamic-programming beat tracking, then a robust least-squares fit of one
  constant grid (produced records are quantised), phase refined to a quarter hop;
  confidence falls with drift and weak on-grid onset support;
- bar phase from the backbeat (kick on 1, snare on 2 and 4) plus votes from the
  largest beat-to-beat changes, since sections change on "1"; ambiguous → none;
- per-bar energy, low end, drum activity and vocal presence (sustained 1–4 kHz
  content relative to the record: vocals and leads, i.e. what clashes);
- section starts from 4-bar feature novelty snapped to the phrase grid, intro end
  and outro start; key from a streaming 4096-point chromagram and
  Krumhansl–Kessler profiles (`KeyDetector`).

Sources: local/downloaded files are decoded whole ahead of time (`TrackLookahead`,
encoder delay trimmed to match ExoPlayer). Streams are analysed from what their
deck has recorded while playing (the whole track when it played from the start),
and the next stream by a silent 3× prelisten of its first 60 s on the standby deck.
`ProfileStore` persists profiles (a complete one is never replaced by a partial);
`ProfileTracker` also mirrors grid, bar phase, key and intro into `AnalysisCache`,
so v1 aligns on the same measurements. Synthetic tests: 92 BPM within 0.15 BPM,
phase within 8 ms after 200 beats, bar phase from a file starting mid-bar, vocal
bars, sections, half-time 72 BPM, noise rejected, key from a progression.

## AutoMix v2 — composed DJ mixes (hip-hop / R&B / pop)

Settings → AutoMix → *Transition engine* selects v1 (default) or v2. v2 replaces
the part exchange on the dual-deck path only.

`DjComposer` scores every combination of outgoing exit bar (sections, 8/4-bar
phrase lines, outro start), incoming entry bar (its start, intro end or a section,
so its verse/hook lands on the key moment), family, length and fill, using both
profiles, then writes the winner as per-deck lanes. Families:

- **Bass-swap blend** (4/8 bars; 16 only ≥110 BPM): incoming high-passed with its
  bass killed, mids muted on bars where both vocals would clash, hats swap; the
  bass swaps on the phrase line; the outgoing leaves by echo, fade or filter.
- **New beat under the vocal**: the incoming drums take over on the one while the
  outgoing vocal rides them; the vocal hands off on the next phrase, ideally where
  the outgoing line ends and the incoming vocal starts.
- **Filter blend**: low-passed incoming opening as the outgoing thins under a
  rising resonant high-pass.
- **Hi-hat tease → drop**: incoming hats only, then a fill and a hard drop.
- **Cuts** (tempos >4 % apart or weak grids): echo throw on the last line,
  backspin, tape stop, stutter (gate + roll) or filter fade, the incoming dropping
  on the outgoing's bar line with no stretch.

Fills before a swap: loop roll, 8th-note gate chop, bass drop-out on the last beat,
echo throw. Scores weigh phrase/section placement, vocal handoffs and clashes,
how much of either song is skipped, stretch (>2 % penalised), key clash (Camelot),
the previous family, and a stable per-pair seed for near ties.

The engine locks the plan once composed (recomposing only when better analysis
arrives >20 s ahead or the start was missed) and hands it to the renderer 1.5 s
before the bar line (5 s if the deck is not preloaded). The renderer starts on that
exact outgoing bar and cues the incoming on its own bar line.

Each deck's processor evaluates its lanes against its own media clock every 64
frames (bass kills land within ~1.5 ms of the downbeat sample). Effects: TPT
resonant filters, damped tempo-synced echo, loop rolls, gates via the dry lane,
and backspin/tape stop reading the deck's recent output at a varying rate.

Beat sync: during blends the renderer compares where each deck's audible playhead
sits on its bar grid; beyond 5 ms it nudges the outgoing tempo by up to 1.2 % just
long enough to close the gap (`PetalSync` log lines show every measurement). The
first measurement of each mix also refines a learned start latency, so the next
incoming starts early by that much and is heard on the bar line.

Limits: vocal presence is a band-envelope heuristic, not separation; phrase and
section detection can miss subtle changes; grids assume a constant tempo (live
recordings get low confidence and therefore cuts); the Spotify/librespot, HAL and
desktop renderers are unchanged.

## v2.2: real-record calibration

A benchmark (`AutomixBench`, run with `PETAL_BENCH_DIR=tools/automix-bench`) decodes
real records with ffmpeg, runs the on-device analysis, writes a report and click
tracks (beat, accented beat 1, section thumps, vocal-entry chimes), and plans the
mix between consecutive songs. On 14 benchmark hip-hop/R&B records the original
DP-tracker grid confidence was ~0 for 12 of them, so v2 fell back to an unaligned
timed fade. Changes:

- **Grid:** a global search of period (±0.6 %) and phase maximising kick/snare
  onset support, refined around a mid-track pivot (decoupling period from phase).
  Confidence = z-score of the winning phase against all others × the squared
  share of four segments that agree. Benchmark tempos now land on DAW values
  (75.50, 85.00, 88.00, 91.00, 93.00, 98.00); 10/14 grids ≥ 0.56.
- **Bar phase:** snares detected as simultaneous mid+high onsets (hats are
  high-only), and a 12-bin chroma (`ChromaEnvelope`, on every deck and in the
  lookahead) whose chord/808 changes vote for beat 1. 13/14 records have a bar
  phase. The same chroma gives the key.
- **Exact clock:** after a seek ExoPlayer resumes at a decoded-frame boundary
  (FLAC: up to ~93 ms from the target) while the processor assumed the target.
  A `MediaCodecAudioRenderer` hook reports each buffer's media time (presentation
  time minus stream offset); the processor rebases its clock on the first buffer.
- **Composer:** cuts carry 1/2/4/8-bar builds (rising resonant HPF or falling LPF,
  thinning low end, swelling echo) with an optional breath before the drop;
  backspin/tape stop hand over to an incoming spin-up (0 → 1.33× → 1, landing on
  the incoming's own audio); beat repeats anchor on beat 1 and halve on the grid,
  only with a tight grid (≥ 0.6); gates use 16th patterns; cuts never enter on a
  near-silent bar and skipping into the incoming is penalised steeply; recent
  mixes are remembered so quick cuts do not repeat and families vary.
- **Diagnostics:** `AutomixLog` appends plans, profiles, clock corrections and
  beat-sync measurements to `Android/data/<package>/files/automix.log`.

## v2.3: clean mixes (Overlay, Handoff, incoming stretch)

Two reference transitions (an 8-bar outro-over-intro and an 8-bar beat-matched
handoff, studied from recordings of a transition editor and their audio) were
simpler than what v2 composed: the incoming at full volume from bar 0, one low
end at a time, the outgoing gone by the key bar, no fills or echo, and the
incoming held at the outgoing's tempo. v2 had several blend families within a
point of each other and let seed jitter, the variety penalty and random fills
decide; it also refused blends above 4 % and penalised any Camelot clash.

**Mix style** (Settings → AutoMix, v2 only). *Clean* (default) allows Overlay,
Handoff, Bass swap and Filter fade, with no fills, no echo sends and the plain
fade exit. *Creative* is everything v2 had before, plus the two new families.

**Overlay** (4/8/16 bars). Chosen when the outgoing's overlap bars have no drums
or low end, or the incoming's intro has no drums. Both decks play at full volume;
the quieter low end of the two is held at zero. The outgoing's highs drop to zero
on bar 0 and return linearly by L/2. The outgoing stops dead on bar L, with no
tail, so the mix can end exactly on its last bar; the incoming's first full bar
should land there (−1.0 otherwise). Allowed up to a 6 % tempo difference, or
with no tempo match at all when both sides are beatless (then nothing is
stretched or synced). The incoming's pre-roll shift is skipped so the landing
holds.

**Handoff** (4 bars). Needs a beat-matched pair with drums on both sides. Bar 0:
outgoing sub, bass and highs to zero; incoming at full volume with highs open,
mids from 0.4 easing to unity (or held at zero until the last half bar when both
vocals overlap). The incoming's low end ramps linearly to unity by the key bar.
The outgoing's dry level fades over the half bar before it. `Lane.ramp` gained
a `linear` curve for these ramps.

**Clean scoring.** A landing term: +3.0 when the incoming's first full bar
(drums ≥ .25, energy ≥ .9 × body) arrives on the outgoing's last bar (with *Let
outros play out*), +2.5 on an 8-bar phrase line at or after the outro, −1.5 when
it arrives mid-overlap. Seed jitter is capped at 0.25 (ties only). The variety
penalty applies to cuts only. In both styles the key-clash penalty now scales
with the larger mean vocal/lead presence of the two overlaps (profiles carry no
per-bar chroma, so `barVocal` is the proxy); a beatless outro barely clashes.

**Tempo.** With *Match the next song to this one* (default) the outgoing keeps
the speed it is playing at and the incoming is stretched onto it
(`Mix.stretchIncoming`, `Mix.holdOutgoing`). The renderer derives the wall bar
from the outgoing's actual speed and the incoming speed as its media bar over
that wall bar; `BeatSync.errorMs` takes the incoming's media bar and the wall bar
separately, and pre-roll seeks scale by the incoming speed. Beat-sync nudges
still ride the outgoing. After the handover the incoming keeps the matched
tempo; with *Return to original tempo* (default) it holds four bars, then steps
back to native over 32 bars, about 0.1 % per bar for a 3 % stretch. The glide
stops if the deck stops being the live one, another mix starts, or a new song
loads (`loadActive` and `promotePreloaded` reset the speed). With the glide off,
the next mix is composed from the held speed (`Options.currentSpeed`). Turning
the match off restores the previous outgoing bend.

**Metrics.** `DjComposer.metrics()` reports four numbers for a planned mix, logged
with every v2 plan and written by `AutomixBench` for both styles: landing error
(beats from the incoming being fully in to the nearest outgoing 8-bar line or its
end), bars with both low ends audible, outgoing bars after the incoming is fully
in, and the tempo change applied to the outgoing. Both reference transitions
score 0 on all four. For cuts the landing is often unknown, because the mix ends
before the incoming reaches its full arrangement.

**Validation.** Unit tests rebuild both reference pairs as profiles and expect
Overlay (exit on the outgoing's last 8 bars) and Handoff, with zero metrics;
check that Clean never writes fills, rolls, spins or echo sends; and cover the
held-tempo composition, stretched-incoming beat sync and linear lanes. On the 14
benchmark records (consecutive album tracks, mostly 6–50 % apart in tempo) only
one pair is beat-matched; Clean composes a 4-bar Handoff there that lands on a
phrase line, and Filter fade cuts elsewhere. No pair doubles the low end.
The incoming stretch and the post-mix glide have not been checked on a device;
Sonic may drain on each speed step, so listen for discontinuities during the glide.

## v3: Context, tempo ride and stems (2026-11-24)

Three structural limits made v2 sound like a fade with effects: only ~12 % of pairs in
the 70-song bench were within the 4 % stretch limit (6 % blended after grid confidence);
four EQ bands cannot isolate drums, bass or vocals; and the handoff's Bed under put two
grooves at unrelated tempos together (1,317 of 1,448 prototype beds had drums ≥ .25, 94 %
of them at "far" tempos). v3 addresses all three. The rule behind it: **unless the decks
are beat-matched, only one deck's rhythm section is audible at any moment.**

**Tempo ride** (Settings → *Ride the tempo*, with *Match the next song*). Pairs up to 8 %
apart are matched. The outgoing glides to `currentSpeed·√r` (never more than 4.5 % from
native) over the 16 bars before the mix (`CrossfadePlayer.rideActive`, steps every 250 ms),
and the renderer stretches the incoming the rest from the outgoing's actual speed, so
each deck moves about half the gap. On the bench this takes ±4 % (12 % of pairs) to ±8 %
(23 %). Once a ride starts its plan is held. The stretch penalty in scoring uses the
per-deck share.

**Stems.** `StemWindow` holds drums, bass line, vocals and the rest for one media range of
a deck, 16-bit at the deck's rate. The processor mixes `original + Σ (gain − 1)·stem`
before the crossover, so unity gains reproduce the original sample for sample and
separation artefacts are heard only on a stem that is turned down. Lanes `DRUMS`,
`BASSLINE`, `VOX`, `OTHER` (default 1) drive them; `SAMPLE` voices a `StemClip` (an
incoming vocal hook) on another deck from `Deck.sampleAt`. That clip is how Vocal
preview gets its "third voice" without a third player.

Separation (`StemWorker`, one low-priority thread) runs after a plan that needs stems locks,
only over the ranges it asked for (`Mix.outStemsMs`, `inStemsMs`, `clipMs`; typically
10–45 s), decoding from the file start on the lookahead's clock. Only files can be read
ahead, so streams get no stems. A stem mix that is not ready 8 s before its bar line
plays `Mix.fallback`, the same pair composed without stems.

- `HpssStemSeparator` (both flavours): median-filter harmonic/percussive separation
  (Fitzgerald 2010) with soft masks; the harmonic part splits into bass (< 150–250 Hz)
  and, by inter-channel centredness and voice range, vocals (Avendano & Jot 2004), the
  rest "other". Masks partition every bin, so the stems sum back to the input. Chunked:
  memory is one 192-frame chunk, not a spectrogram. This is an approximation: a centred
  synth lead counts as vocals, a sustained 808 as bass line.
- rom: `StemModels` loads `files/stems/separator.onnx` + `separator.json` when present (a
  waveform model `[1,2,N] → [1,S,2,N]`, e.g. an HTDemucs export; contract in the KDoc),
  falling back to HPSS. **No model ships and none has been tested**; exporting HTDemucs to
  that contract is open work.

**Context moves.** After the candidate search, a pair with no blend tries, in order:

1. *Vocal preview* (incoming stems) when the incoming opens ≥ 4 dB quieter: its hook
   (first sustained sung full stretch, preferably on a section) plays as two incoming bars
   of vocal stem over a 4-bar build, ending on the cut; with outgoing stems the outgoing
   vocal steps aside under it, without them the build is placed where it is not singing.
   The incoming then starts from its beginning.
2. *Bed under*, fixed: a bed with drums ≥ .12 at an unrelated tempo now needs the
   incoming's stems, which hold its drums at 0 until the key bar (they arrive as it lands);
   without stems only a drumless stretch qualifies. It also no longer pre-empts the search:
   an Overlay or any blend wins first (this was the handoff's one failing test).
3. *Acapella out* (outgoing stems, no key clash): on the phrase line one or two bars before
   a vocal line ends on the key bar, the outgoing's drums, bass line and the rest drop out;
   its vocal carries alone and the incoming drops in on its bar line as the line ends.
   Creative throws the last word into the echo. Nothing rhythmic overlaps.
4. *Vocal preview* again for a much slower and quieter incoming; otherwise a cut.

*Drum tease* (incoming stems, beat-matched, Clean and Creative): incoming drums only for the
first half (tops, no kick); on the halfway line its kick replaces the outgoing's (outgoing
`DRUMS` → 0 with stems, its low bands otherwise); at three quarters the bass lines swap;
the rest opens over the last bar and the incoming vocal arrives on the key bar while the
outgoing vocal has ridden the new beat. It replaces *New beat under the vocal* when
incoming stems exist.

**Choices by evidence, not dice.** Seed jitter is now a tie-break only (.25 Clean, .4
Creative). Fills come from the music at the swap (a line ending → echo throw, a section
landing → roll, a heavy low end → bass drop-out, busy drums → gate) and a set does not
repeat its last two fills (`Played.fill`). Blend exits: echo-out when a line ends at the
end, filter-out when the outgoing is still at body energy, else a plain fade. Cut breaths:
a half-beat of silence before a big drop, an echo-trailed beat after a line. Cut families
carry evidence bonuses (tape stop into a record ≥ 13 % slower, filter fade out of a sparse
end, stutter on busy drums, echo throw on a line ending) and vary through the existing
recency penalty. 8-bar builds when they start on an 8-bar line. Creative cuts may drop
onto the incoming's hook (up to 50 s in) instead of its start.

**Loops.** `DjTransitionPlan.Loop` repeats a deck's audio for N beats until a bar, with
the seam crossfaded into what originally followed the loop and a hand-back crossfade at
the end (up to one bar at 50 BPM). Without outgoing stems, Bass swap, Filter blend and
Handoff loop the outgoing's vocal-free bar 0 over the overlap when its vocal would
otherwise run on over the incoming.

**Smooth shuffle** (Settings, shuffle only). The songs after the next one (up to six) are
ordered greedily by how well each follows the one before: 0 within 4 % (or half/double),
.4 within 8 %, then rising with the gap, +.6 for a Camelot clash; unknown pairs are
neutral. Local files in that window are decoded ahead so their tempo becomes known; it
re-runs when new profiles arrive. The next song is never moved (it is already buffered).

**Downbeat ground truth.** `tools/automix-bench/downbeats/tapper.html` lets you tap beat 1
along a song and writes `labels.tsv`; `DownbeatEval` scores cached profiles against it
(tempo, bar phase in beats, offset in ms). Not yet run: no labels exist.

**Validation.** `DjContextTest` covers the ride (6.5 % pair → each deck ~3.2 %; none
beyond 8 % or with the ride off), the bed drum gate and stem-muted bed, Overlay winning
over a bed, Drum tease lanes with and without outgoing stems, Acapella out (Clean dry,
Creative thrown, refused on a key clash), Vocal preview timing, stem mixing (unity
identical, a removed stem leaves the rest), clip voicing, loop seams, HPSS reconstruction
and click/tone split, and smooth shuffle. None of v3 has been heard on a device yet.

## v3.1: no more cuts (2026-11-24)

Cuts (Echo throw, Backspin, Tape stop, Stutter, Filter fade) stopped one record before the
next started. They are no longer candidates: every pair is now mixed, the two records
always heard together for at least a bar. The families and their DSP (spins, rolls) remain
in the code but nothing composes them; *Intro wash* stays (its incoming plays under the build).

For a pair with no blend, `contextMove` now tries, in order:

1. *Vocal preview* when the incoming opens ≥ 4 dB quieter (incoming stems).
2. *Bed under* (drumless incoming intro, or the incoming's stems hold its drums).
3. *Far ride*: pairs up to 12 % apart are composed again as a beat-matched blend with a
   32-bar ride, up to 6 % on the outgoing (`RIDE_FAR`, `RIDE_MAX_OUT_FAR`); on the bench
   that is 34 % of pairs, against 23 % at 8 %.
4. *Acapella out* (outgoing stems, no key clash), which now mixes: the incoming's opening
   plays under the lone vocal line (a drumless intro, or with the incoming's stems its
   opening bars with drums and bass line held back) and its beat lands as the line ends.
   With nothing beatless to put under the line, it is not offered.
5. *Tail over* (new, no key clash): the incoming drops in on its first full bar and the
   outgoing's tail plays on under it for four or two bars with no beat of its own (a
   drumless outro, or with stems its drums and bass line removed), its low end gone at
   once, mids and highs fading linearly under a rising high-pass. Placed on a phrase line,
   preferably where a vocal line ends.
6. *Vocal preview* for a much slower, quieter incoming.
7. Last resort: a one-bar *Tail over* on EQ alone (low end out, high-pass, fade), which
   still overlaps.

The no-grid fallback (`timed`) is a timed tail over: the incoming starts on its first bar
line while the outgoing fades under it, instead of a filter fade into silence.

Tests: the cut-era tests became "no pair is ever a cut" (seven tempos from 60 to 184 BPM,
both styles, each mix overlapping for at least a quarter bar), Tail over (drumless outro,
stems, the quick EQ tail), Acapella out with the incoming under the line, and the far ride.

## v3.2: a DJ, not a preset (2026-11-24)

With cuts gone, v3.1 fell back on *Tail over* for most unmatched pairs and its context moves
ran in a fixed order, so sets sounded like one move on repeat. v3.2 rewrites the weak points
from DJ teaching material:

- Tempo jumps: loop a ~4-beat section without vocals, let it play, ride the tempo to the next
  track, start it beat-matched underneath on a phrase
  ([Crossfader](https://wearecrossfader.co.uk/blog/3-easy-transitions-to-mix-any-genre/));
  R&B 104 → house 126 over 16 bars with key held
  ([Crossfader](https://wearecrossfader.co.uk/blog/12-high-energy-dj-transitions/)).
- Mix out of the chorus, often one verse and one chorus, not at the outro
  ([Mixed In Key](https://mixedinkey.com/Book/Control-The-Energy-Level-Of-Your-DJ-Sets),
  [Digital DJ Tips](https://www.digitaldjtips.com/2020/06/should-djs-play-the-whole-track/)).
- Energy rules: no high section into an intro, no breakdown into a breakdown, no breakdown
  straight into a peak ([Mixed In Key](https://mixedinkey.com/Book/Control-The-Energy-Level-Of-Your-DJ-Sets)).
- Acapella over a beat with its own beat dropping later, vocal overlays and mashups
  ([Crossfader hip hop](https://wearecrossfader.co.uk/blog/how-to-mix-hip-hop/),
  [DJ.Studio](https://dj.studio/blog/advanced-dj-mixing-techniques)); pacing and contrast over
  constant showpieces ([Digital DJ Tips](https://www.digitaldjtips.com/2020/06/should-djs-play-the-whole-track/)).

**Loop ride** (`LOOP_RIDE`, Clean and Creative, pairs within 25 % of native): the outgoing
loops one or two clean bars (no vocal, or stems take it out; a groove preferred), lets them
play, and the renderer rides its tempo (smoothstep, key held, ≥ 0.1 % steps) to the
incoming's over 8 bars (≤ 18 %) or 12; one bar at the new tempo, then the incoming enters
native and beat-synced, bass swapped halfway through a 4-bar blend (with incoming stems,
drums first, vocal on the last bar). The media under the loop keeps playing, so the move
needs that many bars before the outgoing's end. Renderer: `Mix.rampSpeed/rampFromBar/
rampToBar`; the incoming is timed at the final speed; beat-sync nudges ride the final speed.
The loop buffer holds two bars down to 48 BPM.

**Mashup** (`MASHUP`, Creative, beat-matched, incoming stems, no key clash): the incoming
enters where its vocal starts, as its acapella only, over the outgoing's beat (the outgoing's
vocal removed by stems, or the exit placed where it does not sing); its own beat drops on
bar 4 or 8 while the outgoing hands over its low end and leaves within a bar.

**Double drop** (`DOUBLE_DROP`, Creative, beat-matched, no key clash): both records' drops
(an energy or drum jump on a section) land on the same bar after a filtered incoming build;
one bass from the drop, both for four bars, then the outgoing high-passes away over two.

Showpieces (Mashup, Double drop) are picked when they fit within 3 points of the best blend
and neither of the last two mixes was one.

**DJ pacing** (Settings, default on; needs a whole-file profile): `payoffBar` finds the end of
the second chorus (the loudest sung sections) and exits may start from there; leaving before
it costs 3 points, handing over within 8 bars of it earns 2. *Let outros play out* applies
only with pacing off. Streams (partial profiles) play to their outro as before.

**Energy flow** (`energyFlow`): −1.0 for a high section into an intro, −.8 breakdown into
breakdown, −.5 breakdown into a peak, +.3 for normal/high into normal/high; on every blend
candidate and (half weight) every context move.

**Context moves are weighed, not ordered.** Every move that fits is scored: far ride 2.4,
loop ride 2.1, beatless bed 1.9 (stem-emptied 1.4), acapella out 1.8, tail over 1.5 (stems
1.2; −.4 and a thinner, 2-bar tail on a key clash), vocal preview 2.6 on a big drop (1.8
into a slower record, else .9); minus the set's memory (−1.3 the last move, −.6 the two
before) plus energy flow. The one-bar EQ tail is only the last resort.

**Set simulation** (`DjSetTest`): 120-mix sets with the 70 bench tempos and varied
arrangements. Share of the most used move per set: Clean 30 % (no stems) / 27 % (stems),
Creative 30 % / 25 %; 6–8 different moves per set; no cuts. Without stems (streams) and with
tempos > 25 % apart, the one-bar tail still occurs (~8 % of mixes in the simulation, with
random song order; smooth shuffle reduces such pairs).

## v3.3: tails are a last resort (2026-11-24)

Tail over lets the energy sag right where a DJ lifts it, and v3.2 still chose it for 30 % of
stem-free mixes, partly because the set's memory pushed repeated loop rides onto it.

- **Half/double-time loop ride.** The loop is ridden to the incoming's tempo, half it or double
  it, whichever is nearest (a 128 over a 64 groove), up to 30 % from native; only the loop is
  stretched. Incoming bars per mix bar follow (`per = 1/k`), so its landing and stem range use
  its own bars; the renderer's timing needs nothing new (the wall bar at the final speed is the
  incoming's two bars). On the bench tempos this makes 90 % of pairs beat-matchable, against
  65 % for a 1:1 ride to 25 %.
- **Any bar can loop.** A clean bar (+1) and a groove (+.5) are preferred; a sung loop steps its
  mids back to .45 as the incoming enters so two voices never sit together.
- **Ride length by gap:** 4 bars up to 10 %, 8 up to 18 %, else 12.
- **Creative ride colour:** a rising high-pass while speeding up, a closing low-pass while
  slowing down, released as the incoming arrives.
- **Weights:** beatless bed 2.3 (the classic intro over outro), far ride 2.4, loop ride 2.1,
  tail over .2. A tail is chosen only when nothing that keeps a beat going fits.

Set simulation (`DjSetTest`, which now also caps tails at 15 %): Tail over is 10 % of
stem-free sets and 1 % with stems, against 30 % / 10 % before.
- Stem-free sets: Loop ride 45 %; Overlay, Handoff, Bed under and Bass swap share the rest.
- Sets with stems: Loop ride 33–35 %, Drum tease 20 %, Bed under 15–21 %, Vocal preview 14 %.
- The remaining tails are pairs nothing can beat-match: over 30 % apart even at half or double
  time, or grids below 0.45 confidence.
