"""Inventory training prerequisites without learning from the regression corpus.

Only local reads. Private outputs; approved references are never rewritten.
Historical camera envelopes are not exported as physical training labels.
"""
import argparse
import hashlib
import json
from pathlib import Path
from PIL import Image


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    if not output.is_relative_to(root / "docs/detection/private"):
        raise SystemExit("L’inventaire doit rester dans docs/detection/private.")
    if output.exists():
        raise SystemExit("Sortie existante : choisir un nouveau fichier, preuves conservées.")
    corpus_path = root / "docs/detection/private/corpus/manifest.json"
    reader_root = root / "app/src/androidTest/assets/private/reader-audit"
    corpus = json.loads(corpus_path.read_text())
    reader_path = reader_root / "manifest.json"
    reader = {p["example"]: p for p in json.loads(reader_path.read_text())["pages"]}
    pages = []
    for entry in corpus["pages"]:
        n = entry["example"]
        fixture = root / entry["fixture"]
        raw = fixture.read_bytes()
        if digest(raw) != entry["fixture_sha256"]:
            raise SystemExit(f"Empreinte de la référence {n} incorrecte.")
        with Image.open(fixture) as im:
            rgb = im.convert("RGB")
            dimensions = list(rgb.size)
            pixel_sha = digest(rgb.tobytes())
        reference_name = reader[n].get("reference")
        reference_path = reader_root / reference_name if reference_name else None
        annotation = json.loads(reference_path.read_text()) if reference_path else {}
        original = root / entry["original"] if entry.get("original") else None
        pages.append({
            "example": n,
            "role": "locked_regression_only",
            "training_allowed": False,
            "fixture": entry["fixture"],
            "fixture_sha256": digest(raw),
            "decoded_rgb_sha256": pixel_sha,
            "dimensions": dimensions,
            "original_available": bool(original and original.is_file()),
            "reference_sha256": digest(reference_path.read_bytes()) if reference_path else None,
            "physical_boxes_available": "frames" in annotation,
            "physical_polygon_annotations_available": "outlines" in annotation,
            "speech_visibility_regions_available": "required_speech_regions" in annotation,
            "dimming_regions_available": "required_dimmed_regions" in annotation,
            "balloon_segmentation_masks_available": False,
            "tail_segmentation_masks_available": False,
            "album_group": None,
            "note": "Visibility regions are QA regions, not balloon or tail segmentation labels.",
        })
    pdfs = []
    for source in sorted((root / "app/src/androidTest/assets/private/pdf-albums").glob("*.pdf")):
        pdfs.append({"path": str(source.relative_to(root)), "sha256": digest(source.read_bytes()),
                     "role": "document_evaluation", "training_allowed": False,
                     "annotation_status": "No approved training segmentation supplied by this inventory."})
    model = root / "app/src/main/assets/models/panels-int8.tflite"
    report = {
        "purpose": "local training-readiness inventory; no model trained",
        "model_sha256": digest(model.read_bytes()),
        "corpus_manifest_sha256": digest(corpus_path.read_bytes()),
        "reader_manifest_sha256": digest(reader_path.read_bytes()),
        "pages": pages,
        "evaluation_documents": pdfs,
        "training_pages_exported": 0,
        "rules": [
            "All 60 permanent pages remain regression-only, including resized derivatives.",
            "Additional learning and validation pages must be grouped by album before splitting.",
            "Unknown album groups must not be randomly divided into training and validation.",
            "A physical frame is distinct from the expanded reading envelope.",
            "A QA visibility rectangle is not a whole-balloon segmentation mask.",
            "Model predictions alone cannot establish approved ground truth.",
            "No private raster or annotation is published or uploaded.",
        ],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps({"permanent_pages": len(pages), "physical_box_annotations": sum(p["physical_boxes_available"] for p in pages),
                      "evaluation_documents": len(pdfs), "training_pages_exported": 0}, ensure_ascii=False))


if __name__ == "__main__":
    main()
