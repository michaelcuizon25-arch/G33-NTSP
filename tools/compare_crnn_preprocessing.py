"""Compare captured Android input tensors with the provided Epoch 3 preprocessor.

Diagnostic only: does not change Android preprocessing, weights, or decoding.
Requires numpy and opencv-python-headless. Use only a trusted preprocessor module.
Optional --model needs a TensorFlow runtime with Select TF Ops/Flex support.
"""
import argparse
import hashlib
import importlib.util
import json
import sys
import types
from pathlib import Path

import cv2
import numpy as np


def load_preprocessor(path):
    # process_img does not use Batch. Supply its type import without loading the
    # training dataloader, dataset, or any notebook training cells.
    stub = types.ModuleType("dataloader_iam")
    stub.Batch = type("Batch", (), {})
    previous = sys.modules.get("dataloader_iam")
    sys.modules["dataloader_iam"] = stub
    try:
        spec = importlib.util.spec_from_file_location("epoch3_reference_preprocessor", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.Preprocessor((256, 32), dynamic_width=True, padding=16,
                                   line_mode=False, data_augmentation=False)
    finally:
        if previous is None:
            sys.modules.pop("dataloader_iam", None)
        else:
            sys.modules["dataloader_iam"] = previous


def decode(logits, charset):
    previous = -1
    result = []
    for index in np.argmax(logits[0], axis=-1):
        index = int(index)
        if index != previous and index != len(charset):
            result.append(charset[index])
        previous = index
    return "".join(result)


def edit_distance(a, b):
    row = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        next_row = [i]
        for j, cb in enumerate(b, 1):
            next_row.append(min(row[j] + 1, next_row[-1] + 1, row[j - 1] + (ca != cb)))
        row = next_row
    return row[-1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("session", type=Path)
    parser.add_argument("--preprocessor", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--reviewed-labels", type=Path)
    parser.add_argument("--model", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    records = [json.loads(s) for s in (args.session / "lines.jsonl").read_text().splitlines() if s.strip()]
    labels = {r["id"]: r for r in json.loads(args.reviewed_labels.read_text())} if args.reviewed_labels else {}
    reference_preprocessor = load_preprocessor(args.preprocessor)
    infer = None
    if args.model:
        import tensorflow as tf
        interpreter = tf.lite.Interpreter(model_path=str(args.model))
        interpreter.allocate_tensors()
        input_detail = interpreter.get_input_details()[0]
        output_detail = interpreter.get_output_details()[0]
        metadata = json.loads((args.session / "model.json").read_text())
        charset = metadata["charset"]
        actual_hash = hashlib.sha256(args.model.read_bytes()).hexdigest()
        if actual_hash != metadata["modelSha256"]:
            raise ValueError("Model SHA-256 differs from captured phone model")

        def infer(tensor):
            interpreter.set_tensor(input_detail["index"], tensor.reshape(input_detail["shape"]).astype(np.float32))
            interpreter.invoke()
            logits = interpreter.get_tensor(output_detail["index"])
            return decode(logits, charset), logits

    comparisons = []
    for record in records:
        crop = cv2.imread(str(args.session / record["cropImage"]), cv2.IMREAD_GRAYSCALE)
        if crop is None:
            raise ValueError("Cannot read crop: " + record["cropImage"])
        byte_order = ">f4" if record["inputByteOrder"] == "BIG_ENDIAN" else "<f4"
        actual = np.fromfile(args.session / record["inputTensor"], dtype=byte_order)
        if actual.size != 512 * 32:
            raise ValueError("Unexpected Android input tensor length")
        actual = actual.reshape(512, 32).astype(np.float32)
        ref = reference_preprocessor.process_img(crop).astype(np.float32)
        width = int(ref.shape[0])
        result = {"id": record["id"], "box": record["box"], "referenceWidth": width,
                  "predictedText": record.get("predictedText"),
                  "cropCompleteness": labels.get(record["id"], record).get("cropCompleteness", "unreviewed")}
        if width > 512:
            # Notebook skips these. Never silently compress the training reference.
            result["status"] = "reference_exceeds_fixed_model_width"
        else:
            reference = np.full((512, 32), 0.5, dtype=np.float32)
            reference[:width, :] = ref
            prefix = "line-%04d-reference" % record["id"]
            reference.astype("<f4").tofile(args.output / (prefix + ".f32"))
            cv2.imwrite(str(args.output / (prefix + ".png")),
                        np.clip(np.rint((reference.T + 0.5) * 255), 0, 255).astype(np.uint8))
            delta = actual - reference
            result.update(status="compared", maxAbsDifference=float(np.max(np.abs(delta))),
                          meanAbsDifference=float(np.mean(np.abs(delta))),
                          rmse=float(np.sqrt(np.mean(delta * delta))),
                          identicalBytes=bool(np.array_equal(actual, reference)))
            if infer:
                actual_prediction, logits = infer(actual)
                reference_prediction, _ = infer(reference)
                result.update(androidTensorReplay=actual_prediction, referencePrediction=reference_prediction)
                captured = record.get("outputTensor")
                if captured:
                    phone = np.fromfile(args.session / captured, dtype=byte_order).reshape(logits.shape)
                    result["replayLogitsMaxAbsDifference"] = float(np.max(np.abs(logits - phone)))
                label = labels.get(record["id"], {})
                truth = label.get("groundTruth")
                if label.get("cropCompleteness") == "complete" and truth:
                    result.update(groundTruth=truth, androidReplayCER=edit_distance(truth, actual_prediction) / len(truth),
                                  referenceCER=edit_distance(truth, reference_prediction) / len(truth))
        comparisons.append(result)
    report = {"preprocessorSha256": hashlib.sha256(args.preprocessor.read_bytes()).hexdigest(),
              "modelInferenceRun": bool(infer), "comparisons": comparisons,
              "interpretation": "Tensor differences establish transform mismatch, not accuracy loss. Use complete labeled crops and same-model inference to attribute errors."}
    (args.output / "comparison.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
