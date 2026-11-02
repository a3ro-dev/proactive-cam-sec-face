# Proactive Presence Guard

Quiet on-device guard for your OnePlus CPH2717 (MT6897 Dimensity 8350, Android 16).

- One low-res front-camera frame every 5s. No preview, no overlay.
- Stage 1: ML Kit face presence (ms, CPU).
- Stage 2 (only if face): MobileFaceNet 192-d via NNAPI → MediaTek APU.
- Owner seen → 30s screen-on window. No face 15s → `lockNow()`.
- Stranger 2x in a row → `lockNow()` immediately. System biometric unlocks after.
- Never kills apps / touches brightness → Instagram / YouTube won't glitch.

## You provide (next)
1. `me_30s.mp4` — 30s daylight, specs on/off, zoom in/out, slow head turns.
2. `mobilefacenet.tflite` — put the SAME file in `app/src/main/assets/` and next
   to `tools/enroll.py` so laptop + phone embeddings match. Suggested source:
   a MobileFaceNet int8 192-d build (e.g. sirius-ai / timm export, ~1MB).
3. JDK 17 (build needs it — this laptop only has Java 8 right now).

## Enroll (laptop, no phone heat)
```
pip install opencv-python numpy tflite-runtime
python tools/enroll.py --video me_30s.mp4 --out owner.json
```
Import `owner.json` in the app. Threshold default 0.62.

## Build + install (needs JDK 17)
```
$env:JAVA_HOME = "<jdk17 path>"
./gradlew assembleDebug
C:\Users\akshat\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```
In app: Enable lock permission → allow Camera → Start guard.

## Battery / heat budget
Target <1%/hr: 640×480, 1 frame/5s, embedding only on face, NNAPI, no preview.
If it ever feels warm, raise `TICK_MS` to 8000 in `GuardService.kt`.

## What I deliberately did NOT do
No eye-tracking (cost), no continuous video, no overlay lock screen, no custom
NPU SDK (NNAPI already routes to the 8350 APU), no iPhone-style fancy UI.
