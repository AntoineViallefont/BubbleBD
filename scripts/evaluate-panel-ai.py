"""Local research benchmark; weights and private pages are never added to the APK.
Requires an isolated environment with numpy, Pillow, onnxruntime.
Upstream weights license: AGPL-3.0 despite stale Apache label on ONNX conversion.
"""
from pathlib import Path
import json, time, hashlib
import numpy as np
from PIL import Image, ImageDraw, ImageFont
import onnxruntime as ort
root=Path('docs/detection/private')
model=root/'panel-detector.onnx'
assert hashlib.sha256(model.read_bytes()).hexdigest()=='e66667bc6d5f00013ff27efc15d21e521825369d44dfd5d7f6e43cda2ca512b7'
opts=ort.SessionOptions();opts.intra_op_num_threads=4
session=ort.InferenceSession(str(model),sess_options=opts,providers=['CPUExecutionProvider'])
font=ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial Bold.ttf',19)
reports=[]
for n in range(1,11):
 p=Path(f'app/src/test/resources/private/example-{n}.png')
 if not p.exists():continue
 im=Image.open(p).convert('RGB');w,h=im.size;scale=min(1024/w,1024/h)
 rw,rh=round(w*scale),round(h*scale);dx,dy=(1024-rw)//2,(1024-rh)//2
 tensor=np.full((1024,1024,3),114,dtype=np.uint8)
 tensor[dy:dy+rh,dx:dx+rw]=np.asarray(im.resize((rw,rh),Image.Resampling.BILINEAR))
 tensor=np.ascontiguousarray(tensor.transpose(2,0,1)[None],dtype=np.float32)/255.
 start=time.perf_counter();raw=session.run(None,{'images':tensor})[0][0];elapsed=time.perf_counter()-start
 detections=[]
 for x1,y1,x2,y2,confidence,cls in raw:
  if confidence<.25:continue
  box=[max(0,min(w,(float(x1)-dx)/scale)),max(0,min(h,(float(y1)-dy)/scale)),
       max(0,min(w,(float(x2)-dx)/scale)),max(0,min(h,(float(y2)-dy)/scale))]
  detections.append({'class':int(cls),'confidence':float(confidence),'box':box})
 panels=[d for d in detections if d['class']==0]
 text=[d for d in detections if d['class']==1]
 out=im.copy();draw=ImageDraw.Draw(out)
 # Labels are detector IDs, NOT a proposed reading order.
 for k,item in enumerate(panels,1):
  box=item['box'];draw.rectangle(box,outline='#00dfff',width=3)
  draw.text((box[0]+4,box[1]+4),f"D{k}",font=font,fill='#00dfff',stroke_width=1,stroke_fill='black')
 for item in text:draw.rectangle(item['box'],outline='#ffbc52',width=2)
 out.save(root/f'ai-raw-{n}.png')
 row={'example':n,'panels':len(panels),'text_regions':len(text),'cpu_seconds_on_mac':elapsed,'detections':detections}
 reports.append(row)
 print(f"{n}: {len(panels)} panels, {len(text)} text, {elapsed:.3f}s",flush=True)
(root/'ai-evaluation.json').write_text(json.dumps(reports,indent=2))
