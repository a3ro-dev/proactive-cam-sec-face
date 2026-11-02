# Presence Guard — Direction

## Brief (self-authored; user said "just do it, Appleish is fine")
1. **What, who:** an on-device face-presence guard for one person and their
   OnePlus. It keeps the phone awake while *you* look at it, locks it when
   you walk away, locks it instantly for a stranger.
2. **Core action:** open the app, glance at the state, tap one guard switch.
   Ten seconds, once a day at most — the rest of the time it is invisible.
3. **Feel:** quiet, vigilant, precise. References from outside apps: a
   **Braun RT 20 radio** (object: one dial, nothing decorative), a
   **planetarium show** (place: dark, one beam of light means something),
   a **museum audio guide** (media: numbered, calm, speaks only when asked).
4. **Exists:** Android scaffold in this repo (`com.proactive.presence`);
   no name, no logo, no photos yet. Mockups are iPhone 17 Pro per the skill;
   Android build follows the tokens loosely.
5. **Needs:** screens to look at. No paid image generation — richness is
   drawn (SVG/CSS), which also keeps the set reproducible.
6. No generated photography of people. No credits spent.

## Category default refused
Parental-control / "AI security" slop: near-black ground, electric lime,
face-mesh graphics with scanning lines, shield badges, "Military-grade
protection" and "100% secure" marketing, 2x2 stat tiles ("Threats blocked:
1,234"), streak flames. Refused entirely: no face mesh shown to the user,
no scan-line animation, no shield, no counts that perform vigilance.
The guard has no face. It is a switch with a pulse.

## Concepts (three source families; at most one printed form — zero used)
- **A. "Presence Guard is a planetarium."** Family: place.
  Direction: Instrument, dark. The screen is a dark hall; one beam of
  green light means *you are here*. Time is the hero numeral.
- **B. "Presence Guard is a museum audio guide."** Family: media.
  Direction: Editorial, light bone. Numbered entries, one serif voice,
  hairlines. It speaks only when something changed.
- **C. "Presence Guard is a tide chart."** Family: nature.
  Direction: Colour field. The ground itself is the state: deep lagoon
  green while you are present, amber-ink warning when you drift away.
  Colour carries meaning, not decoration.

## Exploration (Step 2) — shot 2026-11-02, no FAILs, 3 warns (all answered)
- Winner: **C · tide chart** (nature / colour field). The ground itself is the
  state: cobalt sea while you are present, ochre ebb when you drift away.
  Colour carries meaning, the screen is a poster, and the metaphor is the
  product (attention as water). Most describable to a friend, least like the
  "AI security" category default.
- A · planetarium lost: the beam is beautiful but the beam-plus-numeral
  fought for the hero, and amber-on-black reads "instrument" rather than
  "this phone knows me". Borrowed: mono metadata captions.
- B · audio guide lost: calm and specific, but three numbered rows plus a
  headline is one step from a settings list; it never earns a glance from
  across the room. Borrowed: hairline log rows for the event screen.
- History check: `~/.app-designer/history.md` was empty; this is the first
  row. Columns: nature | colour-field | expanded | blue | colour-material.

## Tokens
- Light/present: ground #1B44B4, ink #FFFFFF, ink-2 rgba(255,255,255,.72),
  rule rgba(255,255,255,.25), accent amber #FFB02E (role: state — recording
  and away markers only), on-accent #1A1200.
- Ebb/away: ground #9A6211 (the same sea, gone out), ink/rules identical.
- Dark: same hues, deepened — present #142E7A, ebb #6E4A0E; accent
  desaturated to #F5A623.
- Type: SF Pro Expanded 800 hero 92pt tight -2% tabular; SF Pro 600/400 UI
  17/15/13; SF Mono 12pt letterspaced captions. Margin 20, capsule buttons
  56pt, concentric radii.
- Richness: flat vector tide line, 3pt white on cobalt, dot marks now.
- Glass over cobalt uses --glass rgba(255,255,255,.22).

## Content
- Core: "TIDE · PRESENCE / PRESENT", hero "04:37 / watched today · high
  water", tide line with amber now-dot, "You, 3s ago. Leave 15s and the tide
  goes out.", button "Guard is on", meta "63 enrolled faces · threshold
  0.62 · on-device only".
- Log: "Nº 03 — The room was yours." rows: 01 You, verified 18:49, glasses
  on, near, 0.81 / 02 Locked Yesterday 21:14, 16s after you left, empty desk
  / 03 Stranger, Tue 14:02, locked at once, fingerprint to return.
- Onboarding capture: "Glasses off. Keep looking." / "0:17 left",
  preview, progress, "Stop and rebuild".
- Away: "TIDE · EBB / GONE", hero "00:16", "empty desk · locking", "Locked
  21:14 · 16s after you left".
- First-run: "Fill the tide with only you." / "Thirty seconds of video in
  daylight. Nothing leaves this phone." / "Begin".

## Signature storyboard — "press the water"
Trigger: press-and-hold the hero numeral on the core screen.
Frames: (1) hold begins, numeral dims 5%; (2) 300ms, tide line sinks a
third, light impact tick; (3) 600ms ring completes around the now-dot,
success haptic, ground cross-fades cobalt to deep navy "Paused";
(4) release — guard sleeps, status pill reads HELD.
Interrupt: release before 600ms springs the line back, selection tick, stays on.
Reduced motion: cross-fade only, no sinking. Shown as one panel with 4 minis.
