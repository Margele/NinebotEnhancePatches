#!/usr/bin/env python3
"""Applies the built patch bundle to a Ninebot APK with the ReVanced CLI and checks what came out."""
from pathlib import Path
import argparse, os, re, subprocess, sys, zipfile

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / 'tools/revanced-cli-6.0.0-all.jar'
MODULE = 'dev.ichinomiya.ninebotenhance'
if hasattr(sys.stdout, 'reconfigure'): sys.stdout.reconfigure(encoding='utf-8', errors='replace')

def tool(folder, name):
    return folder / (name + ('.exe' if os.name == 'nt' else ''))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    environment = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    parser.add_argument('--sdk', default=environment, required=not environment)
    parser.add_argument('--jdk', default=os.environ.get('JAVA_HOME'), required=not os.environ.get('JAVA_HOME'))
    parser.add_argument('--build-tools', default='37.0.0')
    parser.add_argument('--apk', type=Path, required=True, help='An unmodified Ninebot 6.10.11 APK')
    parser.add_argument('--out', type=Path, help='Defaults to dist/<apk name>-enhance.apk')
    parser.add_argument('--bundle', type=Path, help='Defaults to the newest dist/*.rvp')
    parser.add_argument('--keystore', type=Path, default=ROOT / 'signing/patched.keystore',
                        help='Created on first use; keep it, or a later build cannot be installed over this one')
    args = parser.parse_args()
    jdk = Path(args.jdk); bt = Path(args.sdk) / 'build-tools' / args.build_tools
    bundles = sorted((ROOT / 'dist').glob('*.rvp'), key=lambda p: p.stat().st_mtime)
    bundle = args.bundle or (bundles[-1] if bundles else None)
    if bundle is None or not CLI.is_file(): raise RuntimeError('Run scripts/build.py first')
    out = args.out or ROOT / 'dist' / (args.apk.stem + '-enhance.apk')
    out.parent.mkdir(parents=True, exist_ok=True); args.keystore.parent.mkdir(parents=True, exist_ok=True)
    command = [tool(jdk / 'bin', 'java'), '-Dfile.encoding=UTF-8', '-Xmx6g', '-jar', CLI, 'patch', '-p', bundle, '-b',
               '--keystore', args.keystore, '-o', out, '-t', ROOT / 'build/patcher', '--purge']
    result = subprocess.run(list(map(str, command + [args.apk])), cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding='utf-8', errors='replace')
    log = result.stdout
    print('\n'.join(line for line in log.splitlines() if 'Compiled classes' not in line))
    if result.returncode or 'succeeded' not in log or 'SEVERE' in log or not out.is_file(): raise RuntimeError('Patching failed')
    manifest = subprocess.run([str(tool(bt, 'aapt2')), 'dump', 'xmltree', str(out), '--file', 'AndroidManifest.xml'], stdout=subprocess.PIPE,
                              encoding='utf-8', errors='replace', check=True).stdout
    for required in ['.embedded.EmbeddedEntry', '.service.FrameBridgeService', '.service.RootBridgeProvider', '.ui.ModuleActivity',
                     '.notification.MirrorNotificationListener', '.service.ScreenCaptureService']:
        if MODULE + required not in manifest: raise RuntimeError('Manifest lacks ' + required)
    for required in ['rikka.shizuku.ShizukuProvider', 'moe.shizuku.manager.permission.API_V23', 'cn.ninebot.ninebot.enhance.root']:
        if required not in manifest: raise RuntimeError('Manifest lacks ' + required)
    if len(re.findall(re.escape(MODULE) + r'\.ui\.\w+Activity" \(Raw', manifest)) != 8: raise RuntimeError('Expected the module\'s eight activities')
    with zipfile.ZipFile(out) as archive:
        names = set(archive.namelist())
        for required in ['META-INF/xposed/java_init.list', 'META-INF/xposed/scope.list', 'assets/ninebotenhance/META-INF/NOTICE.txt']:
            if required not in names: raise RuntimeError('APK lacks ' + required)
    print('Patched APK: ' + str(out) + '\nSigned with: ' + str(args.keystore))

if __name__ == '__main__': main()
