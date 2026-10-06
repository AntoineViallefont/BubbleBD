"""Audit private corpus integrity and render all failures without altering references."""
from pathlib import Path
import argparse,hashlib,json,sys
from datetime import datetime,timezone
root=Path(__file__).resolve().parent.parent
private=root/'docs/detection/private/corpus'
parser=argparse.ArgumentParser();parser.add_argument('--results',type=Path);parser.add_argument('--out',type=Path);args=parser.parse_args()
manifest=json.loads((private/'manifest.json').read_text())
assets=json.loads((root/'app/src/androidTest/assets/private/corpus.json').read_text())
assert len(manifest['pages'])>=25 and [p['example'] for p in manifest['pages']]==list(range(1,len(manifest['pages'])+1)), 'Corpus incomplet'
assert {f'example-{p["example"]}.png' for p in manifest['pages']}=={f.name for f in (root/'app/src/androidTest/assets/private/ai').glob('example-*.png')}, 'Nouvelle planche non inscrite au corpus'
for page,asset in zip(manifest['pages'],assets['pages'],strict=True):
 assert page['example']==asset['example'] and page['expected_count']==asset['expected_count']
 fixture=root/page['fixture'];assert fixture.exists(),fixture
 assert hashlib.sha256(fixture.read_bytes()).hexdigest()==page['fixture_sha256'],f'Capture modifiée : {fixture}'
 if page['reference']:assert (private/page['reference']).exists(),page['reference']
 if asset.get('reference_asset'):assert (root/'app/src/androidTest/assets/private'/asset['reference_asset']).read_bytes()==(private/page['reference']).read_bytes(),'Référence Android différente de la référence conservée'
 if page.get('focus_amendment'):
  assert asset.get('focus_amendment_asset'),'Correction utilisateur absente du test Android'
  approval=private/page['focus_amendment']
  assert approval.read_bytes()==(root/'app/src/androidTest/assets/private'/asset['focus_amendment_asset']).read_bytes(),'Correction Android différente'
  assert json.loads(approval.read_text()).get('confirmation'),'Correction utilisateur non confirmée'
for item in manifest['evidence']+manifest['correction_evidence']:
 assert hashlib.sha256((root/item['file']).read_bytes()).hexdigest()==item['sha256'],f'Archive modifiée : {item["file"]}'
print(f"Intégrité : {len(manifest['pages'])} planches ; {len(manifest['evidence'])} originaux disponibles conservés.")
if not args.results:sys.exit(0)
from PIL import Image,ImageDraw,ImageFont
result=json.loads(args.results.read_text());rows=result['pages']
baseline={r['example']:r for r in json.loads((private/'baseline-v0.3.9.json').read_text())['pages']}
out=args.out or private/'latest';out.mkdir(parents=True,exist_ok=True)
expected={p['example']:p for p in manifest['pages']}
assert sorted(r['example'] for r in rows)==sorted(expected), 'Rapport Android incomplet'
font=ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial Bold.ttf',23)
small=ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial.ttf',16)
failures=[]
for row in rows:
 n=row['example'];entry=expected[n];im=Image.open(root/entry['fixture']).convert('RGB')
 canvas=Image.new('RGB',(im.width,im.height+90),'#10202b');canvas.paste(im,(0,90));d=ImageDraw.Draw(canvas)
 count=len(row.get('hybrid',[]));old=baseline.get(n,{}).get('hybrid',[])
 delta=max((abs(a-b) for box,prior in zip(row.get('hybrid',[]),old) for a,b in zip(box,prior)),default=0) if len(old)==count else None
 row['baseline_count']=len(old);row['max_coordinate_delta_px']=delta
 known=entry['known_failure'] and count==entry['baseline_count'] and count!=entry['expected_count'] and delta is not None and delta<=5
 if entry['known_failure'] and entry.get('baseline_result'):
  previous=json.loads((root/entry['baseline_result']).read_text())
  scale_x=im.width/previous['width'];scale_y=im.height/previous['height']
  prior=[[v*(scale_x if k%2==0 else scale_y) for k,v in enumerate(box)] for box in previous['frames']]
  known=count==len(prior) and max((abs(a-b) for box,old_box in zip(row.get('hybrid',[]),prior) for a,b in zip(box,old_box)),default=0)<=5
 label='LIMITE ANTÉRIEURE' if known else 'ÉCHEC' if not row.get('passed') else 'CONTRÔLES RÉUSSIS'
 d.text((10,8),f'PLANCHE {n} — {count}/{entry["expected_count"]} — {label}',font=font,fill='white')
 d.text((10,39),'Sortie Android mesurée ; cadrages à valider visuellement',font=small,fill='#c8d7e2')
 for k,(l,t,r,b) in enumerate(row.get('hybrid',[]),1):
  c=['#00caff','#ffb900','#ff5599','#55ee88'][(k-1)%4]
  d.rectangle((l,t+90,r-1,b+89),outline=c,width=3)
  d.rectangle((l+2,t+92,l+29,t+120),fill='#10202b');d.text((l+7,t+91),str(k),font=font,fill=c)
 if not row.get('passed'):
  if n==15:
   # Independent visual reference: top-left inset reported missing by Antoine.
   box=(20,108,194,250)
   for x in range(box[0],box[2],12):
    d.line((x,box[1]+90,min(x+7,box[2]),box[1]+90),fill='#ff3333',width=4)
    d.line((x,box[3]+90,min(x+7,box[2]),box[3]+90),fill='#ff3333',width=4)
   for y in range(box[1],box[3],12):
    d.line((box[0],y+90,box[0],min(y+7,box[3])+90),fill='#ff3333',width=4)
    d.line((box[2],y+90,box[2],min(y+7,box[3])+90),fill='#ff3333',width=4)
   d.text((10,63),'Rouge pointillé : encart attendu, absent de la détection',font=small,fill='#ff6a6a')
  row['classification']='limite déjà connue' if known else 'régression ou nouveau cadrage incorrect'
  failures.append(row)
 canvas.save(out/f'page-{n}.png')
result['generated_utc']=datetime.now(timezone.utc).isoformat();result['corpus_sha256']=hashlib.sha256((private/'manifest.json').read_bytes()).hexdigest()
import re
version=re.search(r'val bubbleVersion = "([^"]+)"',(root/'app/build.gradle.kts').read_text()).group(1)
result['version']=version
result['apk_sha256']=hashlib.sha256((root/f'distribution/BubbleBD-{version}.apk').read_bytes()).hexdigest()
result['engine_sha256']={str(f.relative_to(root)):hashlib.sha256(f.read_bytes()).hexdigest() for f in (root/'app/src/main/java/fr/bubblebd').glob('*.kt')}
result['all_passed']=not failures
result['qualification_scope']='existing annotated checks on preserved fixtures; not full reader or phone validation'
result['full_reader_verified']=False
(out/'report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
lines=['# Régression du corpus privé',f'{len(rows)-len(failures)}/{len(rows)} planches passent les contrôles historiques sur captures. Ce résultat ne certifie pas la conformité complète du lecteur. Aucun échec connu ignoré.','']
for row in failures:
 lines.extend([f'Planche {row["example"]} : **{row["classification"]}**. {row.get("failure", "Résultat incomplet")}',expected[row['example']]['correction'],f'![Résultat et zone attendue](page-{row["example"]}.png)',''])
for row in rows:
 if row.get('approved_focus_amendment') and row.get('original_envelope_mismatches'):
  lines.append(f"Planche {row['example']} : correction utilisateur explicite appliquée ; fond facultatif sur {row['original_envelope_mismatches']}. Enveloppe historique non conforme conservée au rapport ; contours des cases encadrées et bulles contrôlés sans réduction.")
lines.append('Tous les aperçus restent disponibles dans ce dossier. Un contrôle technique réussi ne vaut pas validation des cadrages par Antoine.')
changed=[str(r['example']) for r in rows if r['max_coordinate_delta_px'] is None or r['max_coordinate_delta_px']>5]
lines.append('Comparaison à la sortie 0.3.9 (diagnostic, pas vérité terrain) : changements de nombre ou de cadrage >5 px sur les planches '+(', '.join(changed) or 'aucune')+'.')
(out/'report.md').write_text('\n\n'.join(lines)+'\n')
print(f"Corpus : {len(rows)-len(failures)}/{len(rows)} ; rapport : {out/'report.md'}")
sys.exit(1 if failures else 0)
