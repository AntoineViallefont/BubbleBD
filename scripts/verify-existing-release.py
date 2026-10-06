"""Reuse an unchanged, measured APK on explicit release request; retain known failures."""
import hashlib,json,re,sys
from pathlib import Path
root=Path(__file__).resolve().parent.parent
p=Path(sys.argv[1]).resolve();d=json.loads(p.read_text())
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
version=re.search(r'val bubbleVersion = "([^"]+)"',(root/'app/build.gradle.kts').read_text()).group(1)
assert d['version']==version
assert sha(root/f'distribution/BubbleBD-{version}.apk')==d['apk_sha256']
if d.get('validation_type')=='metadata_only':
 import xml.etree.ElementTree as ET
 import zipfile
 allowed={
  'DeletedAlbumsDialog.kt','Reader.kt','MainActivity.kt','LibraryViewModel.kt','ExtendedSources.kt',
  'BibliographicSources.kt','Repository.kt','Models.kt','MetadataInformation.kt','MetadataQueue.kt',
  'MetadataScanStore.kt','AboutScreen.kt','MetadataInformationTest.kt','InterfaceTest.kt','MetadataQueueTest.kt'
 }
 p=Path(d['baseline_provenance']);assert sha(p)==d['baseline_sha256']
 baseline=json.loads(p.read_text())
 for group in ['engine_sha256','validation_sha256','reader_reference_sha256']:
  for name,value in baseline[group].items():
   if sha(root/name)!=value:assert Path(name).name in allowed and group!='reader_reference_sha256',name
 # The reader differs only in its displayed title, never in gestures or panel detection.
 prior=Path(d['baseline_source'])
 assert sha(prior)==json.loads((root/'distribution/firebase-release-0.3.30.json').read_text())['sourceSha256']
 with zipfile.ZipFile(prior) as archive:
  old=archive.read('app/src/main/java/fr/bubblebd/Reader.kt').decode()
 assert hashlib.sha256(old.encode()).hexdigest()==baseline['engine_sha256']['app/src/main/java/fr/bubblebd/Reader.kt']
 assert old.replace('Text(book.title,','Text(book.displayTitle,')==(root/'app/src/main/java/fr/bubblebd/Reader.kt').read_text()
 for name,value in d['application_sha256'].items():assert sha(root/name)==value,name
 assert sha(Path(d['android_xml']))==d['android_xml_sha256']
 tests=ET.parse(d['android_xml']).getroot()
 assert int(tests.get('tests'))>=29 and tests.get('failures')=='0' and tests.get('skipped')=='0'
 for name,value in d['jvm_xml_sha256'].items():assert sha(Path(name))==value,name
 suites=[ET.parse(name).getroot() for name in d['jvm_xml_sha256']]
 assert sum(int(x.get('tests',0)) for x in suites)>=186
 assert sum(int(x.get('failures',0))+int(x.get('errors',0)) for x in suites)==0
 assert sha(Path(d['build_log']))==d['build_log_sha256']
 assert 'BUILD SUCCESSFUL' in Path(d['build_log']).read_text()
 print('Fiches et recherches vérifiées ; lecteur inchangé hors libellé du titre ; limites historiques conservées.')
elif d.get('validation_type')=='ui_only':
 import xml.etree.ElementTree as ET
 assert sha(Path(d['baseline_provenance']))==d['baseline_sha256']
 for name,value in d['ui_sha256'].items():assert sha(root/name)==value,name
 assert sha(Path(d['android_xml']))==d['android_xml_sha256']
 tests=ET.parse(d['android_xml']).getroot()
 assert tests.get('tests')=='3' and tests.get('failures')=='0' and tests.get('skipped')=='0'
 p=Path(d['baseline_provenance']);baseline=json.loads(p.read_text())
 for group in ['engine_sha256','validation_sha256','reader_reference_sha256']:
  for name,value in baseline[group].items():
   if name in d['ui_sha256']:continue
   assert sha(root/name)==value,name
 print('Modification de fiche vérifiée : trois contrôles Android réussis ; moteur inchangé depuis la suite complète.')
else:
 for group in ['engine_sha256','validation_sha256','reader_reference_sha256']:
  for name,value in d[group].items():assert sha(root/name)==value,name
assert sha(root/'app/src/main/assets/models/panels-int8.tflite')=='b1a7d8d4492e04a777ae0d3efd9dc1fbd6e8f361971eadb813279ce3dfd1b464'
r=json.loads((p.parent/'reader-corpus/report.json').read_text())
assert len(r['rows'])==340
failed=sorted({x['example'] for x in r['rows'] if x['failures']})
assert failed==[5,12,13,14,15,28,32,33,35,42,49,50,56,57,59]
print('Identité de la version vérifiée ; 61 pages / 340 variantes ; 15 échecs connus conservés, aucune conformité globale annoncée.')
