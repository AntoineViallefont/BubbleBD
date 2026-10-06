"""Reproduce the local float32 mobile export; no training or private page input."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil

parser = argparse.ArgumentParser()
parser.add_argument('checkpoint', type=Path)
parser.add_argument('directory', type=Path)
args = parser.parse_args()
assert hashlib.sha256(args.checkpoint.read_bytes()).hexdigest() == '73e0fb587ea3afe0d17aa9f0c3b1f5a8001b3ecbc3c77091e0730654b0da9146'
args.directory.mkdir(parents=True, exist_ok=True)
os.environ['YOLO_CONFIG_DIR'] = str(args.directory.resolve() / 'config')
Path(os.environ['YOLO_CONFIG_DIR']).mkdir(exist_ok=True)
os.environ['ULTRALYTICS_AUTOINSTALL'] = 'false'
os.environ['YOLO_OFFLINE'] = 'true'
os.environ['TF_CPP_MIN_LOG_LEVEL'] = '2'
from ultralytics import YOLO, settings
settings.update({'sync': False})
destination = args.directory.resolve() / 'panels-fp32.pt'
assert not destination.exists(), 'Use a new directory to preserve previous exports'
shutil.copyfile(args.checkpoint, destination)
YOLO(str(destination)).export(format='saved_model', imgsz=640, batch=1,
                            nms=True, conf=.25, iou=.7, max_det=300,
                            device='cpu', quantize=32)
