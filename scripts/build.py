#!/usr/bin/env python3
"""Builds the patch bundle: the extension dex from the module sources, the Kotlin patches, and the dex copy ReVanced Manager loads."""
from pathlib import Path
import argparse, hashlib, os, shutil, subprocess, sys, urllib.request, zipfile

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / 'tools/revanced-cli-6.0.0-all.jar'
CLI_URL = 'https://github.com/ReVanced/revanced-cli/releases/download/v6.0.0/revanced-cli-6.0.0-all.jar'
CLI_SHA256 = 'c25549bc17d59d2eb94fa5f86e60e9b77a02772ca88f7050f8f1276f923a9958'
PACKAGE = 'dev/ichinomiya/ninebotenhance'
# Files the patch copies into the APK; the module's About page reads them.
BUNDLED = ['META-INF/NOTICE.txt', 'META-INF/licenses/Apache-2.0.txt', 'META-INF/licenses/NinebotEnhance-Apache-2.0.txt', 'META-INF/licenses/Shizuku-MIT.txt']
if hasattr(sys.stdout, 'reconfigure'): sys.stdout.reconfigure(encoding='utf-8', errors='replace')

def tool(folder, name):
    return folder / (name + ('.exe' if os.name == 'nt' else ''))

def run(args, **options):
    args = list(map(str, args))
    if Path(args[0]).stem == 'javac': args.insert(1, '-J-Dfile.encoding=UTF-8')
    if Path(args[0]).stem == 'java': args.insert(1, '-Dfile.encoding=UTF-8')
    result = subprocess.run(args, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding='utf-8', errors='replace', **options)
    if result.returncode:
        print(result.stdout.strip())
        raise RuntimeError('Command failed: ' + Path(args[0]).name)
    return result.stdout

def properties(path):
    return dict(line.strip().split('=', 1) for line in path.read_text(encoding='utf-8').splitlines() if line.strip() and not line.startswith('#'))

def cli():
    """The ReVanced CLI release jar carries the patcher the patches compile against and the tool that applies them."""
    if not CLI.is_file():
        CLI.parent.mkdir(exist_ok=True)
        print('Downloading ' + CLI_URL)
        urllib.request.urlretrieve(CLI_URL, CLI)
    if hashlib.sha256(CLI.read_bytes()).hexdigest() != CLI_SHA256: raise RuntimeError('Checksum mismatch: ' + CLI.name)
    return CLI

def module_dir(value):
    for candidate in [value, ROOT / 'module', ROOT.parent / 'NinebotEnhance']:
        if candidate and (Path(candidate) / 'app/src/main/java').is_dir(): return Path(candidate).resolve()
    raise RuntimeError('Module sources not found: run "git submodule update --init" or pass --module')

def extension(module, sdk, jdk, platform, build_tools, work):
    """Compiles the module with this project's Flavor and the embedded runtime into the one dex the patch merges into Ninebot."""
    android = sdk / 'platforms' / platform / 'android.jar'; bt = sdk / 'build-tools' / build_tools
    for required in [android, tool(jdk / 'bin', 'javac'), bt / 'lib/d8.jar']:
        if not required.is_file(): raise RuntimeError('Missing build input: ' + str(required))
    checksums = __import__('json').loads((module / 'libs/checksums.json').read_text(encoding='utf-8'))
    for filename, expected in checksums.items():
        if hashlib.sha256((module / 'libs' / filename).read_bytes()).hexdigest() != expected: raise RuntimeError('Dependency checksum mismatch: ' + filename)
    classes, dex = work / 'classes', work / 'dex'
    for folder in [classes, dex]: folder.mkdir(parents=True)
    runtime = []
    for aar in sorted((module / 'libs').glob('shizuku-*.aar')):
        with zipfile.ZipFile(aar) as archive:
            jar = work / (aar.stem + '.jar'); jar.write_bytes(archive.read('classes.jar')); runtime.append(jar)
    compile_only = [module / 'libs/libxposed-api-101.0.1.jar', module / 'libs/androidx-annotation-1.3.0.jar']
    shared = module / 'app/src/main/java'; own = ROOT / 'extension/src/main/java'
    replaced = {p.relative_to(own).as_posix() for p in own.rglob('*.java')}
    sources = [p for p in sorted(shared.rglob('*.java')) if p.relative_to(shared).as_posix() not in replaced] + sorted(own.rglob('*.java'))
    if PACKAGE + '/ipc/Flavor.java' not in replaced: raise RuntimeError('The extension must replace ipc/Flavor.java')
    response = work / 'sources.rsp'; response.write_text('\n'.join('"' + p.as_posix() + '"' for p in sources), encoding='utf-8')
    run([tool(jdk / 'bin', 'javac'), '-encoding', 'UTF-8', '--release', '17', '-classpath', os.pathsep.join(map(str, [android] + compile_only + runtime)),
         '-d', classes, '@' + str(response)])
    # Host tests of the dispatcher; the tests' own android.util.Log stands in front of the platform stub jar.
    tests = work / 'test-classes'; tests.mkdir()
    run([tool(jdk / 'bin', 'javac'), '-encoding', 'UTF-8', '--release', '17', '-classpath', os.pathsep.join(map(str, [classes, android])), '-d', tests]
        + sorted((ROOT / 'tests/java').rglob('*.java')))
    print(run([tool(jdk / 'bin', 'java'), '-cp', os.pathsep.join(map(str, [tests, classes, android])), 'StaticHooksTest']).strip())
    jar = work / 'program.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
        for p in sorted(classes.rglob('*.class')): archive.write(p, p.relative_to(classes).as_posix())
    # Ninebot's minSdk is 26; the module itself stays idle below Android 11 (EmbeddedEntry).
    run([tool(jdk / 'bin', 'java'), '-cp', bt / 'lib/d8.jar', 'com.android.tools.r8.D8', '--release', '--min-api', '26', '--lib', android,
         '--classpath', compile_only[0], '--classpath', compile_only[1], '--output', dex, jar] + runtime)
    produced = sorted(dex.glob('*.dex'))
    if [p.name for p in produced] != ['classes.dex']: raise RuntimeError('The extension must fit one dex, got ' + ', '.join(p.name for p in produced))
    return produced[0]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    environment = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    parser.add_argument('--sdk', default=environment, required=not environment)
    parser.add_argument('--jdk', default=os.environ.get('JAVA_HOME'), required=not os.environ.get('JAVA_HOME'))
    parser.add_argument('--module', type=Path, help='NinebotEnhance checkout; defaults to the module submodule, then ../NinebotEnhance')
    parser.add_argument('--platform', default='android-36.1')
    parser.add_argument('--build-tools', default='37.0.0')
    args = parser.parse_args()
    sdk, jdk = Path(args.sdk), Path(args.jdk); module = module_dir(args.module); cli()
    version = properties(module / 'version.properties')['versionName']
    work = ROOT / 'build/extension'; shutil.rmtree(work, ignore_errors=True); work.mkdir(parents=True)
    dex = extension(module, sdk, jdk, args.platform, args.build_tools, work)
    generated = ROOT / 'patches/build/generated/resources'; shutil.rmtree(generated, ignore_errors=True)
    (generated / 'extensions').mkdir(parents=True); shutil.copyfile(dex, generated / 'extensions/ninebotenhance.rve')
    for path in BUNDLED:
        target = generated / 'ninebotenhance' / path; target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(module / 'app/src/main/resources' / path, target)
    gradle = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    run([gradle, '--quiet', ':patches:jar', '-PmoduleDir=' + module.as_posix(), '-PpatchesVersion=' + version], env={**os.environ, 'JAVA_HOME': str(jdk)})
    jar = ROOT / 'patches/build/libs/ninebot-enhance-patches.rvp'
    dist = ROOT / 'dist'; dist.mkdir(exist_ok=True)
    bundle = dist / ('ninebot-enhance-patches-' + version + '.rvp'); shutil.copyfile(jar, bundle)
    # ReVanced Manager runs on Android and loads the patches from a classes.dex inside the same file.
    android_dex = work / 'patches-dex'; android_dex.mkdir()
    classes_jar = work / 'patches.jar'; shutil.copyfile(jar, classes_jar)  # D8 goes by the file extension
    run([tool(jdk / 'bin', 'java'), '-cp', sdk / 'build-tools' / args.build_tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--release', '--min-api', '27',
         '--output', android_dex, classes_jar])
    with zipfile.ZipFile(bundle, 'a', zipfile.ZIP_DEFLATED) as archive: archive.write(android_dex / 'classes.dex', 'classes.dex')
    with zipfile.ZipFile(bundle) as archive:
        names = archive.namelist()
        for required in ['extensions/ninebotenhance.rve', 'classes.dex', PACKAGE + '/hook/HookPolicy.class', PACKAGE + '/patches/EmbedKt.class'] + ['ninebotenhance/' + p for p in BUNDLED]:
            if required not in names: raise RuntimeError('Bundle lacks ' + required)
    sha = hashlib.sha256(bundle.read_bytes()).hexdigest()
    (dist / 'SHA256SUMS.txt').write_text(sha + '  ' + bundle.name + '\n', encoding='utf-8')
    print('Module: ' + str(module) + ' (' + version + ')\nBundle: ' + str(bundle) + '\nSHA256: ' + sha)

if __name__ == '__main__': main()
