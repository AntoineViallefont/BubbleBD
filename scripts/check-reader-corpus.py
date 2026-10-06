"""Report production reader evidence separately from annotation coverage."""
from pathlib import Path
import argparse, json, hashlib
from collections import Counter
from PIL import Image, ImageDraw

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('directory', type=Path)
args = parser.parse_args()
report_path = args.directory / 'report.json'
report = json.loads(report_path.read_text())
identity_path = args.directory.parent / 'reader-run-provenance.json'
if identity_path.exists():
    identity = json.loads(identity_path.read_text())
    for name, expected_hash in identity['engine_sha256'].items():
        assert hashlib.sha256((root / name).read_bytes()).hexdigest() == expected_hash, f'Moteur modifié pendant la passe : {name}'
    assert hashlib.sha256((root / 'app/src/androidTest/assets/private/reader-audit/manifest.json').read_bytes()).hexdigest() == identity['reader_reference_manifest_sha256'], 'Manifest du lecteur modifié pendant la passe'
    for name, expected_hash in identity.get('reader_reference_sha256', {}).items():
        assert hashlib.sha256((root / name).read_bytes()).hexdigest() == expected_hash, f'Référence modifiée pendant la passe : {name}'
    for name, expected_hash in identity.get('validation_sha256', {}).items():
        assert hashlib.sha256((root / name).read_bytes()).hexdigest() == expected_hash, f'Contrôle modifié pendant la passe : {name}'
    report['build_identity'] = identity
manifest = json.loads((root / 'docs/detection/private/corpus/manifest.json').read_text())
reader_annotations = {entry['example']: entry for entry in json.loads((root / 'app/src/androidTest/assets/private/reader-audit/manifest.json').read_text())['pages']}
rows = report['rows']
coverage = []
for entry in manifest['pages']:
    n = entry['example']
    measured = [r for r in rows if r['example'] == n]
    width = Image.open(root / entry['fixture']).width
    required = {(w, prior) for w in {width, 720, 1800} for prior in [False, True]}
    found = {(r['target_width'], r['prior']) for r in measured}
    assert found == required, f'Planche {n} : variantes manquantes ou inattendues'
    assert len(measured) == len(required), f'Planche {n} : mesures dupliquées'
    assert all((args.directory / f"{n}-{r['target_width']}-{str(r['prior']).lower()}.png").exists() for r in measured), f'Planche {n}: rendus manquants'
    gaps = []
    for key, label in [('physical_reference_available', 'contours physiques non annotés'),
                       ('speech_reference_available', 'bulles non annotées'),
                       ('dimming_reference_available', 'zones hors case non annotées')]:
        if not all(r[key] for r in measured):
            gaps.append(label)
    failures = [dict(width=r['target_width'], prior=r['prior'], details=r['failures']) for r in measured if r['failures']]
    coverage.append(dict(example=n, variants=len(measured), failures=failures, missing_checks=gaps,
                         status='échec' if failures else 'contrôles incomplets' if gaps else 'critères annotés réussis'))
summary = Counter(c['status'] for c in coverage)
report['coverage'] = coverage
report['coverage_summary'] = dict(summary)
report['reference_manifest_sha256'] = hashlib.sha256((root / 'docs/detection/private/corpus/manifest.json').read_bytes()).hexdigest()
report['complete_user_validation'] = False
report['qualified_to_announce_all_corpus_conformant'] = not any(c['failures'] or c['missing_checks'] for c in coverage)
report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
lines = ['# Contrôle du lecteur Android',
         f"{len(coverage)} planches, {len(rows)} variantes mesurées. {dict(summary)}.",
         'Décodage de CBZ construits depuis les captures conservées, détection de production et glissements dans ComicView. Agrandir une capture ne recrée pas le fichier original. Aucun essai téléphone revendiqué.',
         'Les rendus réunissent les vues successives obtenues par les gestes du lecteur. Les vues communes peuvent sauter un numéro individuel conformément à la navigation. Les régions natives sont comparées seulement lorsque leur attendu est annoté. Leur existence ne prouve pas une couverture exhaustive des bulles de la page.']
for item in coverage:
    n = item['example']
    lines += [f"## Planche {n} — {item['status']}", '; '.join(item['missing_checks']) or 'Contours, bulles et masque : annotations disponibles.']
    for failure in item['failures']:
        name = f"{n}-{failure['width']}-{str(failure['prior']).lower()}.png"
        measured = next(r for r in rows if r['example'] == n and r['target_width'] == failure['width'] and r['prior'] == failure['prior'])
        entry = next(p for p in manifest['pages'] if p['example'] == n)
        source = Image.open(root / entry['fixture']).convert('RGB')
        factor = min(1, 500 / source.width)
        thumbnail = source.resize((round(source.width * factor), round(source.height * factor)))
        comparison = Image.new('RGB', (thumbnail.width * 2, thumbnail.height + 40), '#13202a')
        comparison.paste(thumbnail, (0, 40)); comparison.paste(thumbnail, (thumbnail.width, 40))
        draw = ImageDraw.Draw(comparison)
        annotation = reader_annotations[n].get('reference')
        reference = json.loads((root / 'app/src/androidTest/assets/private/reader-audit' / annotation).read_text()) if annotation else {}
        if not reference.get('frames') and entry.get('reference'):
            reference = json.loads((root / 'docs/detection/private/corpus' / entry['reference']).read_text())
        expected_boxes = reference.get('frames', reference.get('panels', []))
        kind = 'Cadres annotés' if reference.get('frames') else 'Enveloppes historiques'
        draw.text((8, 8), f"{kind} : {entry['expected_count']} cases", fill='#66ccff')
        draw.text((thumbnail.width + 8, 8), f"Boîtes mesurées : {measured['count']} cases", fill='#ff7777')
        for i, box in enumerate(expected_boxes, 1):
            mapped = [box[j] * factor + (40 if j % 2 else 0) for j in range(4)]
            draw.rectangle(mapped, outline='#66ccff', width=2); draw.text((mapped[0] + 3, mapped[1] + 3), str(i), fill='#66ccff')
        for i, box in enumerate(measured['frames'], 1):
            mapped = [box[j] / (measured['width'] if j % 2 == 0 else measured['height']) * (thumbnail.width if j % 2 == 0 else thumbnail.height) + (thumbnail.width if j % 2 == 0 else 40) for j in range(4)]
            draw.rectangle(mapped, outline='#ff7777', width=2); draw.text((mapped[0] + 3, mapped[1] + 3), str(i), fill='#ff7777')
        comparison_name = name.removesuffix('.png') + '-comparison.png'
        comparison.save(args.directory / comparison_name)
        lines += [f"Largeur {failure['width']}, préférence rectangulaire {failure['prior']} : " + '; '.join(failure['details']),
                  'Défaut révélé par cette variante ; antériorité non établie sans mesure équivalente de la version précédente. Les références anciennes sans contours physiques représentent leurs enveloppes approuvées.',
                  f'![Attendu conservé et contours mesurés]({comparison_name})', f'![Rendu réel du lecteur]({name})']
(args.directory / 'report.md').write_text('\n\n'.join(lines) + '\n')
print(f"Lecteur : {len(coverage)} planches / {len(rows)} variantes ; {dict(summary)}")
raise SystemExit(0 if report['qualified_to_announce_all_corpus_conformant'] else 1)
