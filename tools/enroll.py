"""Build owner.json from your 30s enrollment video — runs on the laptop.

Why here and not on the phone: keeps the phone cool and the APK tiny.
We extract ~60 diverse frames (glasses / no-glasses / near / far / angles),
run the same MobileFaceNet the app uses, and save the mean embedding.

Usage:
    pip install opencv-python numpy tflite-runtime
    # place mobilefacenet.tflite next to this script (same file you put in
    # app/src/main/assets/mobilefacenet.tflite so embeddings match)
    python tools/enroll.py --video me_30s.mp4 --out owner.json

Then in the app tap "Import owner.json" and pick the file.
"""
import argparse
import json
import cv2
import numpy as np

IMG = 160

def load_model(path):
    try:
        from tflite_runtime.interpreter import Interpreter
    except ImportError:
        from tensorflow.lite.python.interpreter import Interpreter  # type: ignore
    it = Interpreter(model_path=path, num_threads=4)
    it.allocate_tensors()
    return it

def embed(it, face_bgr):
    inp = it.get_input_details()[0]
    out = it.get_output_details()[0]
    img = cv2.resize(face_bgr, (IMG, IMG)).astype(np.float32)
    img = (img - 127.5) / 128.0  # MobileFaceNet normalization
    img = np.expand_dims(img, 0)
    it.set_tensor(inp["index"], img)
    it.invoke()
    v = it.get_tensor(out["index"])[0].astype(np.float64)
    return v / (np.linalg.norm(v) + 1e-9)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", required=True)
    ap.add_argument("--model", default="mobilefacenet.tflite")
    ap.add_argument("--out", default="owner.json")
    ap.add_argument("--threshold", type=float, default=0.62)
    args = ap.parse_args()

    cap = cv2.VideoCapture(args.video)
    n = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    picks = np.linspace(0, max(n - 1, 0), num=min(max(n, 1), 60)).astype(int)
    haar = cv2.CascadeClassifier(
        cv2.data.haarcascades + "haarcascade_frontalface_default.xml"
    )
    it = load_model(args.model)
    vecs = []
    for i in picks:
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(i))
        ok, frame = cap.read()
        if not ok:
            continue
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        faces = haar.detectMultiScale(gray, 1.2, 5, minSize=(80, 80))
        if len(faces) == 0:
            continue
        # largest face = you, closest to camera
        x, y, w, h = max(faces, key=lambda b: b[2] * b[3])
        side = max(w, h)
        cx, cy = x + w // 2, y + h // 2
        x0 = max(cx - side // 2, 0)
        y0 = max(cy - side // 2, 0)
        crop = frame[y0:y0 + side, x0:x0 + side]
        if crop.size == 0:
            continue
        vecs.append(embed(it, crop))
    cap.release()
    if len(vecs) < 10:
        raise SystemExit(f"only {len(vecs)} usable faces — re-shoot in daylight, slower head turns")
    mean = np.mean(np.stack(vecs), axis=0)
    mean = mean / (np.linalg.norm(mean) + 1e-9)
    # quick self-check: how tight is the cluster?
    sims = [float(np.dot(v, mean)) for v in vecs]
    print(f"faces={len(vecs)} min_sim={min(sims):.3f} mean_sim={np.mean(sims):.3f}")
    with open(args.out, "w") as f:
        json.dump({"mean": mean.tolist(), "threshold": args.threshold,
                   "faces": len(vecs)}, f)
    print(f"wrote {args.out} — import it in the app")

if __name__ == "__main__":
    main()
