"""Host-only image/CRNN audit; not Android JNI verification.

prepare: mirror Android enhancement in OpenCV, save original/enhanced boards and
same-coordinate title crops. A Robolectric audit exports actual Kotlin CRNN
inputs. infer: run those inputs through unchanged CPU/Flex weights. Run the
Kotlin audit again to decode the logits using the existing CTC implementation.
No training, image downloads or app dependency changes occur here.
Requires host numpy, opencv-python-headless and TensorFlow with Flex.
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np


def enhance(rgb):
    import cv2
    lab = cv2.cvtColor(rgb, cv2.COLOR_RGB2LAB)
    luminance, a, b = cv2.split(lab)
    filtered = cv2.bilateralFilter(luminance, 3, 10.0, 1.5)
    contrast = cv2.createCLAHE(clipLimit=1.5, tileGridSize=(8, 8)).apply(filtered)
    blended = cv2.addWeighted(luminance, 0.5, contrast, 0.5, 0.0)
    return cv2.cvtColor(cv2.merge((blended, a, b)), cv2.COLOR_LAB2RGB)


def prepare(root, output):
    import cv2
    output.mkdir(parents=True, exist_ok=True)
    fixtures = root / "app/src/test/resources"
    cases = []

    def save_case(name, rgb, truth=None):
        for enabled in (False, True):
            image = enhance(rgb) if enabled else rgb
            path = output / f"{name}-{'enhanced' if enabled else 'original'}.png"
            cv2.imwrite(str(path), cv2.cvtColor(image, cv2.COLOR_RGB2BGR))
            cases.append(dict(name=name, enabled=enabled, image=str(path.resolve()), truth=truth))

    for path in sorted(fixtures.glob("board_test_*")) + sorted(fixtures.glob("test_whiteboard_*")):
        image = cv2.cvtColor(cv2.imread(str(path)), cv2.COLOR_BGR2RGB)
        height, width = image.shape[:2]
        if max(width, height) > 1800:
            scale = 1800 / max(width, height)
            image = cv2.resize(image, (int(width * scale), int(height * scale)), interpolation=cv2.INTER_LINEAR)
        save_case(path.stem, image)
        if path.name == "board_test_5.jpg":
            title = image[330:400, 210:965].copy()
    truth = "Mobile Application Development"
    save_case("title-clear", title, truth)
    save_case("title-faint", np.rint(255 - (255 - title.astype(np.float64)) * 0.35).astype(np.uint8), truth)
    lighting = np.linspace(0.45, 1.0, title.shape[1])[None, :, None]
    save_case("title-uneven-lighting", np.rint(title * lighting).astype(np.uint8), truth)
    save_case("title-half-resolution", cv2.resize(title, None, fx=0.5, fy=0.5), truth)
    (output / "cases.json").write_text(json.dumps(cases, indent=2) + "\n")
    (output / "preparation.json").write_text(json.dumps(dict(opencv=cv2.__version__, numpy=np.__version__), indent=2) + "\n")
    print(f"Prepared {len(cases)} images with host OpenCV {cv2.__version__}")


def infer(root, output):
    import tensorflow as tf
    model = root / "app/src/main/assets/ocr/note2snap_crnn_v2_epoch03_flex.tflite"
    interpreter = tf.lite.Interpreter(model_path=str(model), num_threads=1)
    interpreter.allocate_tensors()
    records = []
    for path in sorted(output.glob("input-*.f32")):
        tensor = np.fromfile(path, dtype="<f4").reshape(1, 512, 32)
        interpreter.set_tensor(interpreter.get_input_details()[0]["index"], tensor)
        interpreter.invoke()
        logits = interpreter.get_tensor(interpreter.get_output_details()[0]["index"])
        assert logits.shape == (1, 128, 80) and np.isfinite(logits).all()
        result = path.with_name(path.name.replace("input-", "output-"))
        logits.astype("<f4").tofile(result)
        records.append(dict(input=path.name, output=result.name, shape=list(logits.shape)))
    (output / "native-inference.json").write_text(json.dumps(dict(
        scope="Host OpenCV mirror + CPU/Flex; Kotlin input preprocessing and CTC decoder; Android JNI unverified",
        imagePreparation=json.loads((output / "preparation.json").read_text()),
        tensorflow=tf.__version__, numpy=np.__version__,
        modelSha256=hashlib.sha256(model.read_bytes()).hexdigest(), cases=records), indent=2) + "\n")
    print(f"Inferred {len(records)} unchanged-model inputs")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["prepare", "infer"])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    (prepare if args.operation == "prepare" else infer)(args.root.resolve(), args.output.resolve())


if __name__ == "__main__":
    main()
