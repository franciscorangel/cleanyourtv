"""Build br.casa.tvkeys (APK) and the shell layout controller (layoutctl.dex) without Gradle.

Adapted from Lzchyi's sony-apps-layout build (MIT). Needs JAVA_HOME (JDK 17-22) and ANDROID_HOME with
platform 34 and build-tools 34.0.0. Signs with the standard debug keystore (~/.android/debug.keystore).
"""
import hashlib
import json
import os
import shutil
import subprocess
import zipfile
from pathlib import Path

project = Path(__file__).resolve().parent
sdk = Path(os.environ.get('ANDROID_HOME') or Path.home() / 'Library/Android/sdk')
tools = sdk / 'build-tools/34.0.0'
android = sdk / 'platforms/android-34/android.jar'
java = Path(os.environ['JAVA_HOME']) / 'bin'
keystore = Path.home() / '.android/debug.keystore'
build = project / 'build'

if build.exists():
    shutil.rmtree(build)
for name in ['classes', 'shell-classes', 'dex', 'shell-dex']:
    (build / name).mkdir(parents=True)


def run(args):
    subprocess.run([str(a) for a in args], check=True)


run([tools / 'aapt2', 'compile', '--dir', project / 'res', '-o', build / 'resources.zip'])
run([tools / 'aapt2', 'link', '-I', android, '--manifest', project / 'AndroidManifest.xml',
     '-o', build / 'base.apk', build / 'resources.zip'])
run([java / 'javac', '-source', '8', '-target', '8', '-Xlint:-options', '-classpath', android,
     '-d', build / 'classes', *sorted(project.glob('src/**/*.java'))])
run([java / 'jar', 'cf', build / 'classes.jar', '-C', build / 'classes', '.'])
run([tools / 'd8', '--lib', android, '--min-api', '26', '--output', build / 'dex', build / 'classes.jar'])
shutil.copy2(build / 'base.apk', build / 'unsigned.apk')
with zipfile.ZipFile(build / 'unsigned.apk', 'a') as archive:
    archive.write(build / 'dex/classes.dex', 'classes.dex')
run([tools / 'zipalign', '-f', '4', build / 'unsigned.apk', build / 'aligned.apk'])
run([tools / 'apksigner', 'sign', '--ks', keystore, '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
     '--out', build / 'tvkeys.apk', build / 'aligned.apk'])
run([tools / 'apksigner', 'verify', build / 'tvkeys.apk'])
run([java / 'javac', '-source', '8', '-target', '8', '-Xlint:-options', '-classpath', android,
     '-d', build / 'shell-classes', project / 'shell/LayoutCtl.java'])
run([java / 'jar', 'cf', build / 'shell-classes.jar', '-C', build / 'shell-classes', '.'])
run([tools / 'd8', '--lib', android, '--min-api', '26', '--output', build / 'shell-dex', build / 'shell-classes.jar'])
shutil.copy2(build / 'shell-dex/classes.dex', build / 'layoutctl.dex')
print(json.dumps({f.name: {'bytes': f.stat().st_size, 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()[:16]}
                  for f in [build / 'tvkeys.apk', build / 'layoutctl.dex']}))
