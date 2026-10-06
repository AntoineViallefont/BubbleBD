"""Compare FP32 checkpoint and embedded INT8 on the same exported Android values.

This measures the complete exports (including backend/NMS differences), not an
isolated quantization error. No training, network use or production replacement.
"""
import argparse
import hashlib
import json
import os
import struct
import time
from pathlib import Path


def sha(data):
    return hashlib.sha256(data).hexdigest()


def iou(a, b):
    area = max(0, min(a[2], b[2])-max(a[0], b[0])) * max(0, min(a[3], b[3])-max(a[1], b[1]))
    union = (a[2]-a[0])*(a[3]-a[1]) + (b[2]-b[0])*(b[3]-b[1])-area
    return area / union if union else 0


def match_count(expected, proposals):
    matches = [-1]*len(proposals)

    def augment(i, seen):
        for j, candidate in enumerate(proposals):
            if j in seen or iou(expected[i], candidate) < .5:
                continue
            seen.add(j)
            if matches[j] < 0 or augment(matches[j], seen):
                matches[j] = i
                return True
        return False

    return sum(augment(i, set()) for i in range(len(expected)))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--study", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    study = args.study.resolve()
    output = args.output.resolve()
    if not output.is_relative_to(root / "docs/detection/private") or output.exists():
        raise SystemExit("Choisir une nouvelle sortie privée.")
    config = study / "config"
    config.mkdir(exist_ok=True)
    os.environ["YOLO_CONFIG_DIR"] = str(config)
    os.environ["YOLO_OFFLINE"] = "true"
    os.environ["ULTRALYTICS_AUTOINSTALL"] = "false"
    import numpy as np
    import torch
    import ultralytics
    from ultralytics import YOLO, settings
    settings.update({"sync": False})
    torch.set_num_threads(2)
    upstream = json.loads((study / "upstream-model.json").read_text())
    checkpoint = study / "manga_panel_detector_fp32.pt"
    expected_sha = next(x["lfs"]["sha256"] for x in upstream["siblings"] if x["rfilename"] == checkpoint.name)
    if sha(checkpoint.read_bytes()) != expected_sha:
        raise SystemExit("Empreinte des poids incorrecte.")
    native_root = study / "native-inputs"
    report = json.loads((native_root / "report.json").read_text())
    embedded_sha = sha((root / "app/src/main/assets/models/panels-int8.tflite").read_bytes())
    if report["model_sha256"] != embedded_sha:
        raise SystemExit("Le modèle embarqué a changé depuis la capture.")
    upstream_int8 = next(x["lfs"]["sha256"] for x in upstream["siblings"] if x["rfilename"].endswith(".tflite"))
    if embedded_sha != upstream_int8:
        raise SystemExit("La provenance commune des exports doit être vérifiée.")
    model = YOLO(str(checkpoint))
    if model.names != {0: "frame", 1: "text"}:
        raise SystemExit("Classes du modèle inattendues.")
    reader_root = root / "app/src/androidTest/assets/private/reader-audit"
    manifest = {r["example"]: r for r in json.loads((reader_root / "manifest.json").read_text())["pages"]}
    dtype = "<f4" if report["byte_order"] == "LITTLE_ENDIAN" else ">f4"
    rows = []
    for row in report["rows"]:
        n = row["example"]
        binary = (native_root / f"{n}.tensor").read_bytes()
        w, h = struct.unpack(">ii", binary[:8])
        if sha(binary[8:]) != row["tensor_sha256"]:
            raise SystemExit(f"Entrée {n} modifiée.")
        values = np.frombuffer(binary[8:], dtype=dtype).reshape(h, w, 3)
        tensor = torch.from_numpy(np.ascontiguousarray(values.transpose(2, 0, 1)[None]))
        start = time.perf_counter()
        # Tensor sources already contain Android letterboxing and normalization.
        # Predictor does not resize tensor sources or divide them by 255 again.
        predictions = model.predict(tensor, device="cpu", imgsz=[h, w], conf=.25, iou=.7,
                                    max_det=300, verbose=False, save=False)[0]
        seconds = time.perf_counter()-start
        fp32 = predictions.boxes.data.cpu().numpy().tolist()

        def decode(items, normalized):
            output = []
            for x1, y1, x2, y2, confidence, kind in items:
                if confidence < .25 or int(round(kind)) not in (0, 1):
                    continue
                if normalized:
                    x1 *= w; x2 *= w; y1 *= h; y2 *= h
                box = [min(1., max(0., (x1-row["dx"])/row["scale"]/row["source_width"])),
                       min(1., max(0., (y1-row["dy"])/row["scale"]/row["source_height"])),
                       min(1., max(0., (x2-row["dx"])/row["scale"]/row["source_width"])),
                       min(1., max(0., (y2-row["dy"])/row["scale"]/row["source_height"]))]
                if box[2]-box[0] > .015 and box[3]-box[1] > .01:
                    output.append({"box": box, "score": confidence, "class": int(round(kind))})
            return output

        floating = decode(fp32, False)
        quantized = decode(row["raw_normalized_output"], True)
        entry = manifest[n]
        reference = json.loads((reader_root / entry["reference"]).read_text()) if entry.get("reference") else {}
        expected = None
        if "frames" in reference:
            expected = [[p[0]/row["source_width"], p[1]/row["source_height"], p[2]/row["source_width"], p[3]/row["source_height"]]
                        for i, p in enumerate(reference["frames"]) if i+1 != reference.get("background_panel")]
        result = {"example": n, "tensor_sha256": row["tensor_sha256"], "fp32_cpu_mac_seconds": seconds,
                  "physical_reference_available": expected is not None, "eligible_frames": len(expected) if expected is not None else None,
                  "fp32_predictions": floating, "int8_predictions": quantized}
        if expected is not None:
            result["fp32_matched_iou50"] = match_count(expected, [p["box"] for p in floating if p["class"] == 0])
            result["int8_matched_iou50"] = match_count(expected, [p["box"] for p in quantized if p["class"] == 0])
        rows.append(result)
        print(n, result.get("fp32_matched_iou50"), result.get("int8_matched_iou50"), flush=True)
    measured = [r for r in rows if r["physical_reference_available"]]
    summary = {"pages": len(rows), "physical_reference_pages": len(measured), "eligible_frames": sum(r["eligible_frames"] for r in measured),
               "fp32_matched_iou50": sum(r["fp32_matched_iou50"] for r in measured),
               "int8_matched_iou50": sum(r["int8_matched_iou50"] for r in measured)}
    output.write_text(json.dumps({"purpose": "same-input complete-export comparison; not isolated quantization or reader validation",
                                 "checkpoint_sha256": expected_sha, "embedded_model_sha256": embedded_sha,
                                 "upstream_revision": upstream["sha"], "torch_version": torch.__version__, "ultralytics_version": ultralytics.__version__,
                                 "fp32_nms": {"confidence": .25, "iou": .7, "max_det": 300},
                                 "native_export_nms_settings": "Not independently recovered; possible backend/NMS differences remain.",
                                 "summary": summary, "rows": rows}, indent=2))
    print(summary)


if __name__ == "__main__":
    main()
