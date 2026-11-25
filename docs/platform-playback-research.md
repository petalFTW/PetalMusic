# Platform playback findings (2026-10-08)

## Spotify: official client patches vs librespot

PetalMusic authenticates through Spotify OAuth and then uses reusable credentials
with librespot-java. Authentication and catalogue access do not imply that Spotify
will grant the audio keys needed by this playback transport.

[SpotX](https://github.com/SpotX-Official/SpotX) patches the **official desktop
client**, removing ads and changing local UI/experimental features. Desktop Free
playback already exists in that client. Its [FAQ](https://telegra.ph/SpotX-FAQ-09-19)
explicitly excludes server-side Premium features such as very-high quality and
downloads. This is not a Premium credential or an audio-key workaround that can
be transplanted into librespot.

The [librespot project](https://github.com/librespot-org/librespot) explicitly
supports Premium playback only. Changing the local catalogue attribute, removing
PetalMusic's Free fallback, or advertising a different device type does not prove
that the server will grant audio keys.

The official [Morphe patch list](https://github.com/MorpheApp/morphe-patches) does
not currently contain Spotify patches. The public
[Spicetify Morphe Spotify bundle](https://github.com/spicetify/morphe-patches)
contains theme, ad-card, Premium-tab, sharing and server-local-file patches; it
explicitly says hiding the Premium tab does not change the subscription. The
[other Spotify bundle reviewed](https://github.com/cvnfork/morphe-spotify-patches)
adds settings, Home pins and WebDAV local files. Neither supplies a librespot
Free playback implementation. A different claimed "Spotify Morphe" unlocker
needs its exact repository and supported version checked separately.

**Conclusion:** no verified native Spotify Free streaming workaround was found
in these sources. Free playback through PetalMusic still uses its equivalent
recording fallback. A browser/official-client playback backend would be a
separate integration; it would not supply the decoded PCM PetalMusic currently
uses for its Spotify mixer.

## YouTube Music: maintained extraction and session continuity

[InnerTune's player implementation](https://github.com/z-huang/InnerTune/blob/dev/innertube/src/main/java/com/zionhuang/innertube/YouTube.kt)
tries signed-in Android Music, then iOS, then TV metadata with Piped audio. It
also maintains visitor data. This is a client fallback strategy, not a universal
solution to YouTube IP/account verification.

[NewPipeExtractor 0.26.5](https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/v0.26.5)
uses a VISIONOS playback path. Its
[stream helper](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeStreamHelper.java)
fetches visitor data, generates a content playback nonce and builds client-specific
requests. It also has proof-of-origin token interfaces for supported alternative
clients. Morphe's YouTube patches include a separate PoToken provider integration.
Tokens are bound to a session/context; copying a token or hardcoding another
client version is not a durable solution.

### Applied changes

- Updated PetalMusic's extractor from 0.24.5 to 0.26.5 and made it the first
  playback resolver, ahead of the legacy hand-written clients.
- Removed the reflective forced WEB version so the extractor can manage it.
- Fixed the downloader overwriting account cookies with consent cookies. Cookies
  are merged by their case-sensitive names, with saved session values taking
  precedence, and attached only to YouTube hosts (not native Google API hosts).
- Removed cookie-bearing debug logs and stopped modifying downloaded HTML/JSON
  by stripping newlines. Responses are closed after consumption.
- Corrected the downloader's reversed response-body/latest-URL arguments, which
  made the extractor parse a URL instead of the downloaded HTML/JSON.
- Allowed public music search without login, which previously prevented Spotify
  fallback unless a YouTube account was connected.
- Preserved the extractor's actual audio MIME type.
- Required an auth-capable SAPISID cookie rather than accepting any SID substring.
  Sign-in now has an explicit Connect button so the user can finish interactive
  verification before the browser is dismissed.

### Runtime verification still required

Android SDK 35 and build-tools 34/35 are now installed at
`/home/quiq/Android/Sdk`. The full Android unit-test run passes all 105 tests,
including the four downloader/session regression tests. The optimized release
build and release lint checks pass. NewPipe's lite protobuf dependency is
excluded in favour of the matching full 4.35.1 runtime used by librespot, avoiding
duplicate classes during APK packaging. `prebuilt/app-release.apk` is signed
with the supplied petalOS platform key; signature and 16 KB alignment checks pass.

Test anonymous YT Music search and playback, signed-in library/playlists and
playback, Spotify Free fallback, and playback after app restart. On an affected
network, finish the challenge in the sign-in browser and tap Connect before
retrying. Cookies alone do not guarantee success on every bot-gated network.
PetalMusic does not yet implement a PoToken minter/provider. A remaining
proof-of-origin failure needs that additional integration, using matching visitor
identity and separate player/media tokens where required; rotating legacy client
identities is not evidence of a fix.
