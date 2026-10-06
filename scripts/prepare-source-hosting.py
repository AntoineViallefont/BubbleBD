"""Publish only audited source archives, never the workspace or personal files."""
from pathlib import Path
import re,shutil,zipfile,hashlib
root=Path(__file__).resolve().parent.parent
version=re.search(r'val bubbleVersion = "([^"]+)"',(root/'app/build.gradle.kts').read_text()).group(1)
source=root/f'distribution/BubbleBD-{version}-source.zip'
with zipfile.ZipFile(source) as archive:
    names=archive.namelist()
    assert not any('/private/' in n or n.endswith(('local.properties','.jks','.keystore','.apk')) for n in names)
    assert 'LICENSE' in names and 'NOTICE' in names
out=root/'distribution/source-hosting';out.mkdir(exist_ok=True)
shutil.copyfile(source,out/source.name)
sha=hashlib.sha256(source.read_bytes()).hexdigest()
(out/(source.name+'.sha256')).write_text(sha+'  '+source.name+'\n')
(out/'index.html').write_text(f'''<!doctype html><html lang="fr"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BubbleBD — code source</title><body style="font-family:system-ui;max-width:720px;margin:60px auto;padding:24px"><h1>BubbleBD</h1><p>Code source de la version {version}, sous licence AGPL. Instructions de compilation et licences incluses.</p><p><a href="{source.name}">Télécharger le code source {version}</a></p><p>Aucune BD ni donnée de compte incluse.</p><p><a href="{source.name}.sha256">Empreinte SHA-256</a></p></body></html>''')
print('Source bundle ready:',source.name)
