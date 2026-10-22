"""Run unchanged Epoch 3 TFLite/Flex on captured tensors; no training or resizing.

Requires a TensorFlow build with Flex, e.g. tensorflow-cpu==2.16.1.
Python native shape inspection is separate from Android's Java shape cache.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path

import numpy as np
import tensorflow as tf


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--model", required=True, type=Path)
    p.add_argument("--charset", required=True, type=Path)
    p.add_argument("--session", required=True, type=Path)
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--golden-output-dir", type=Path)
    p.add_argument("--golden-line-id", type=int, default=2)
    args = p.parse_args()
    charset = list(args.charset.read_text().removeprefix("\ufeff").rstrip("\r\n"))
    assert len(charset) == 79
    interpreter = tf.lite.Interpreter(model_path=str(args.model), num_threads=1)

    def details():
        return {kind: [{"name": d["name"], "shape": d["shape"].tolist(),
                        "signature": d["shape_signature"].tolist(), "dtype": np.dtype(d["dtype"]).name}
                       for d in getter()] for kind, getter in
                (("inputs", interpreter.get_input_details), ("outputs", interpreter.get_output_details))}

    def invoke(tensor):
        input_detail = interpreter.get_input_details()[0]
        assert input_detail["shape"].tolist() == [1, 512, 32]
        interpreter.set_tensor(input_detail["index"], tensor.reshape(1, 512, 32))
        interpreter.invoke()
        output_detail = interpreter.get_output_details()[0]
        logits = interpreter.get_tensor(output_detail["index"])
        assert logits.shape == (1, 128, 80) and logits.nbytes == 40960
        assert np.isfinite(logits).all()
        previous = -1
        chars = []
        for index in np.argmax(logits[0], axis=-1):
            index = int(index)
            if index != previous and index != 79:
                chars.append(charset[index])
            previous = index
        raw = "".join(chars)
        return logits, raw, " ".join(raw.split())

    created = details()
    interpreter.allocate_tensors()
    allocated = details()
    blank, raw, text = invoke(np.full((512, 32), .5, np.float32))
    after_first = details()
    records = [json.loads(s) for s in (args.session / "lines.jsonl").read_text().splitlines() if s.strip()]
    report = {"tensorflowVersion": tf.__version__, "numpyVersion": np.__version__,
              "modelSha256": hashlib.sha256(args.model.read_bytes()).hexdigest(),
              "created": created, "allocated": allocated, "afterFirstInvoke": after_first,
              "blankInputPrediction": text, "comparisons": [],
              "scope": "CPU native TFLite/Flex inference; Android Java copying requires device validation"}
    goldens = []
    for record in records:
        for mode, field in (("legacy", "legacyInputTensor"), ("selected", "inputTensor")):
            tensor_path = args.session / record[field]
            dtype = ">f4" if record["inputByteOrder"] == "BIG_ENDIAN" else "<f4"
            tensor = np.fromfile(tensor_path, dtype=dtype).astype(np.float32)
            assert tensor.size == 512 * 32
            logits, raw, text = invoke(tensor)
            result = {"id": record["id"], "mode": mode, "actualMode": "LEGACY" if mode == "legacy" else record["actualMode"],
                      "inputSha256": hashlib.sha256(tensor_path.read_bytes()).hexdigest(),
                      "outputShape": list(logits.shape), "outputSignature": interpreter.get_output_details()[0]["shape_signature"].tolist(),
                      "outputBytes": logits.nbytes, "finite": bool(np.isfinite(logits).all()),
                      "rawPrediction": raw, "prediction": text}
            report["comparisons"].append(result)
            if args.golden_output_dir and record["id"] == args.golden_line_id:
                args.golden_output_dir.mkdir(parents=True, exist_ok=True)
                name = mode + "-logits.f32.gz"
                (args.golden_output_dir / name).write_bytes(gzip.compress(logits.astype("<f4").tobytes(), mtime=0))
                goldens.append(dict(result, tensor=name))
    if args.golden_output_dir:
        (args.golden_output_dir / "manifest.json").write_text(json.dumps({
            "modelSha256": report["modelSha256"], "tensorflowVersion": tf.__version__,
            "source": "board_test_5.jpg physical-line input capture, no ground-truth accuracy assertion", "cases": goldens}, indent=2) + "\n")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"created": created, "allocated": allocated, "afterFirstInvoke": after_first,
                      "cropInvocations": len(report["comparisons"]),
                      "nonemptyPredictions": sum(bool(r["prediction"]) for r in report["comparisons"])}, indent=2))


if __name__ == "__main__":
    main()
