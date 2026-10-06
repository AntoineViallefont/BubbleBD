"""Synthetic release update check, invoked through test-device.sh on its dedicated AVD."""
from pathlib import Path
import json
import os
import subprocess
import sys
import tempfile
import time

adb, serial, old_apk, new_apk = sys.argv[1:]
root=Path(__file__).resolve().parent.parent
key_dir=Path(os.environ.get('BUBBLEBD_SIGNING_DIR',str(Path.home()/'.config/bubblebd/signing'))).resolve()
assert not key_dir.is_relative_to(root), 'The publication key must stay outside the checkout.'
def run(*args, check=True):
    return subprocess.run([adb,'-s',serial,*args],check=check,capture_output=True,text=True).stdout.strip()
assert run('emu','avd','name').splitlines()[0]=='BubbleBD_Test_API_36_1'
for apk in [old_apk,new_apk]: assert Path(apk).is_file(),apk
unsigned=root/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
signer=Path(adb).parent.parent/'build-tools/36.0.0/apksigner'
with tempfile.TemporaryDirectory(prefix='bubblebd-update-') as directory:
    signed=Path(directory)/'update-test.apk'
    subprocess.run([str(signer),'sign','--ks',str(key_dir/'release.p12'),'--ks-key-alias','bubblebd',
        '--ks-pass',f'file:{key_dir / "password"}','--out',str(signed),str(unsigned)],check=True)
    # The test companion is signed with the same key, allowing instrumentation without root.
    run('uninstall','fr.bubblebd.test',check=False)
    run('uninstall','fr.bubblebd',check=False)
    print(run('install',old_apk),flush=True)
    print(run('install',str(signed)),flush=True)
    def instrument(phase):
        output=run('shell','am','instrument','-w','-e','class','fr.bubblebd.ReleaseUpdateTest',
            '-e','updatePhase',phase,'fr.bubblebd.test/androidx.test.runner.AndroidJUnitRunner')
        print(output,flush=True)
        assert 'OK (1 test)' in output and 'FAILURES' not in output,phase
    def startup():
        run('shell','am','start','-W','-n','fr.bubblebd/.MainActivity')
        time.sleep(3)
        assert run('shell','pidof','fr.bubblebd'),'App did not stay running'
        run('shell','uiautomator','dump','/sdcard/bubblebd-update-ui.xml')
        assert 'Update test — title kept' in run('shell','cat','/sdcard/bubblebd-update-ui.xml'),'Synthetic book not displayed'
        run('shell','am','force-stop','fr.bubblebd')
    instrument('seed')
    startup()
    instrument('verify')
    print(run('install','-r',new_apk),flush=True)
    startup()
    instrument('verify')
    run('uninstall','fr.bubblebd.test')
    run('shell','am','start','-W','-n','fr.bubblebd/.MainActivity')
print(json.dumps({'update':'passed','old_apk':Path(old_apk).name,'new_apk':Path(new_apk).name,
 'preserved':['title','series','number','page 7 of 12','started','synopsis','metadata lock','theme','sort','dimming'],
 'scope':'Synthetic library on dedicated emulator; no phone or real library tested.'},indent=2))
