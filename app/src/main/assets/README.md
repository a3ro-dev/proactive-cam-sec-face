# app/src/main/assets

## mobilefacenet.tflite (5.2 MB)

Open-source MobileFaceNet: 112x112 float input, 192-d L2-normalized
embedding, `(pixel - 127.5) / 128` normalization, cosine threshold ~0.62
(equivalent to the author's L2 distance 0.89).

Source: [estebanuri/face_recognition](https://github.com/estebanuri/face_recognition)
(`android/app/src/main/assets/mobile_face_net.tflite`), Apache 2.0.
Renamed to `mobilefacenet.tflite` to match `FaceVerifier.kt`.

To refresh: download the same path from that repo and replace this file.
`tools/enroll.py` uses the identical math, so laptop and on-device
embeddings stay interchangeable.
