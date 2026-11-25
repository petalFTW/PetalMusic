# UI theme (redesign phase 1)

Every screen takes its colours from `ui/theme/Tokens.kt`. Screens should not use hex
values. The exceptions are source brand marks (`Components.kt`, `SourceLogo.kt`), colours
taken from artwork, `MixWaveform`, and the empty-state drawing.

## Roles

Read roles with `Petal.colors.<role>`. Inside a `Canvas` or `drawBehind` lambda, read
the value first: `val ink = Petal.colors.ink`.

| Role | Night | Paper | Use |
|---|---|---|---|
| `bg` | #120D16 | #FBF8FA | page |
| `surface1` | #1B1420 | #F1EAF0 | tiles, cards, nav bar |
| `surface2` | #261D2C | #E8DFE7 | raised: secondary buttons, mini-player |
| `ink` / `text2` / `text3` | #F6EFF7 / #B9AABD / #7E7083 | #1D1320 / #665769 / #9A8C9D | text levels |
| `accent` | dye light | dye deep | play button, selection; never a gradient |
| `glassControl` / `glassBar` / `glassSheet` | white 13 % / bg 52 % / 50 % | ink 7 % / bg 72 % / 80 % | glass fills (blur arrives in phase 2) |
| `danger`, `dangerSoft` | | | errors, swipe-to-remove |

`PetalColors.toMaterial()` maps the roles onto the Material 3 scheme, so stock components
match. `PetalPalette` still exists for older call sites. Its colours now come from the
tokens, and `accentBrush` is a solid colour.

## Settings

- **Theme** (`theme_mode`): System, Night or Paper. System follows the device's dark mode.
- **Dye** (`dye`): Sleeve (the default: the playing cover's accent), Orchid, Amethyst, Rose, Twilight, Jade or System.
  - System takes the Material You primary colour.
  - Anyone who had the old Dynamic color switch on starts on System.

The status and navigation bar icons switch between light and dark with the theme.

## Always dark

`NightSurface { }` gives a subtree Night roles in either theme. It is used for anything
drawn on a cover: the Now Playing screen and the Home feature cards. `OnArt` is the
equivalent set of roles for code outside composition.

## Type

The fonts are in `res/font`, and their SIL OFL 1.1 licences are in `assets/licenses`.

- **Bricolage Grotesque** (variable: weight, width, optical size): display and title styles. See `ui-redesign.md` (phase 2) for the scale.
- **Instrument Sans** (variable): body, lists and buttons. This is the default for `Text`.
- **IBM Plex Mono**: data. It is used through `labelSmall`, or `PetalMono` directly, for
  BPM, key, sample rate and times.

The variable faces are pinned to named weights with `FontVariation`.
