"""Generate independent golden tensors using the supplied SimpleHTR source.

Run with OpenCV 4.12.0.88 and numpy 2.2.6. No training or model inference.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np

from compare_crnn_preprocessing import load_preprocessor


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preprocessor", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    reference = load_preprocessor(args.preprocessor)
    cases = []
    for name, w, h, color in [
        ("narrow", 5, 80, False), ("color", 200, 40, True),
        ("fractional", 137, 43, False), ("exact_width", 496, 32, False),
        ("single_pixel", 1, 1, False), ("fractional_tall", 87, 51, True),
    ]:
        y, x = np.indices((h, w))
        if color:
            rgb = np.stack(((x * 17 + y * 5) % 256, (x * 3 + y * 11) % 256,
                            (x * 7 + y * 19) % 256), axis=-1).astype(np.uint8)
            image = rgb[:, :, ::-1]  # cv2.imwrite expects BGR
        else:
            image = ((x * 31 + y * 17) % 256).astype(np.uint8)
        path = args.output / (name + ".png")
        cv2.imwrite(str(path), image)
        gray = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
        transformed = reference.process_img(gray).astype(np.float32)
        tensor = np.full((512, 32), .5, dtype=np.float32)
        tensor[:transformed.shape[0]] = transformed
        (args.output / (name + ".f32.gz")).write_bytes(
            gzip.compress(tensor.astype("<f4").tobytes(), mtime=0))
        cases.append(dict(name=name, width=w, height=h, dynamicWidth=transformed.shape[0]))
    manifest = dict(opencvVersion=cv2.__version__, numpyVersion=np.__version__,
                    preprocessorSha256=hashlib.sha256(args.preprocessor.read_bytes()).hexdigest(),
                    inferenceRun=False, cases=cases)
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
