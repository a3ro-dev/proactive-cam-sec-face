# Proactive Presence — Direction (condensed app-designer pass)

Self-authored brief: user explicitly asked for app-designer skill, no fancy UI.
Skill note: `app-designer` targets iPhone / iOS 26 Liquid Glass / SwiftUI.
This is an Android 16 background utility on OnePlus CPH2717 (MT6897, NNAPI/NPU).
Full iPhone flow (3x renders, Liquid Glass, App Store panels) is the wrong
medium here — a lock-screen utility must be invisible 99% of the time.
So: condensed pass — brief + tokens + 2 screens, Material You, no marketing.

## Brief
- What: on-device face-presence guard. Owner present → phone stays usable.
  Owner absent 15s → `lockNow()`. Stranger face → `lockNow()` immediately,
  system biometric required on return.
- Core action (first 10s): open app, tap Enroll, tap Start guard. Done.
- Feel: quiet, system-native, trustworthy. References: Pixel security screens,
  Teenage Engineering restraint, airport signage (outside apps, per skill).
- Exists: nothing. Greenfield in `D:\proactive-cam-sec-face`.
- Needs: Android build (not SwiftUI), 2 screens only.

## Category default refused
Parental-control / “AI security” cliché: dark bg, neon-green face mesh,
scanning-line animation, shield icon, “100% SECURE!!”. Refused.
Instead: light Material You surfaces, one deep-green accent, no face mesh
shown to user, no scan animation. The guard has no face — it’s a switch.

## Tokens
- Light: bg #F7F2EA, surface #FFFFFF, ink #1A1C19, muted #5F6368
- Dark: bg #131613, surface #1E201E, ink #E6E9E4, muted #9AA39B
- Accent (one role): #2E6B34 (verified green). Semantic danger: #B3261E only
  for stranger-lock event row.
- Type: system default (Roboto / Google Sans on device). No custom font —
  system trust > branding here. Sizes: 28/20/16/13, tracking -0.01/0.
- Radius 20, margin 20, spacing 4/8/12/20/32.

## Screens (2 only, native Compose/Views — no HTML mockup)
1. Guard (core): big status line (“Watching quietly” / “You, verified 3s ago”),
   Start/Pause button in thumb zone, last-event row. No hero stat cards.
2. Enroll: 6 slot thumbnails (glasses / no-glasses / near / far / left / right),
   single Enroll-from-video button, threshold slider (default 0.62 cosine).
   Icon: filled circle + eyelid line, no shield.

## Signature interaction
Toggle Start → status dot pulses twice (300ms), haptic tick, then still.
No continuous animation while guarding (battery rule). Reduced motion: no pulse.

## Scan / critique (self)
- No emoji icons, no gradients, no greeting header, no card stacks, one accent.
- Not verified on-device feel yet — will screenshot on CPH2717 after install.
