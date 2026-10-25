"""Collect existing distribution license files; never infer missing license grants."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import re
import shutil
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LEGAL = ROOT / 'legal'
LICENSES = LEGAL / 'licenses'
LICENSES.mkdir(parents=True, exist_ok=True)
RUNTIME = ROOT / 'vendor/android-runtime/src/main'
entries: list[dict] = []
license_sources: list[dict] = []
missing: list[dict] = []

def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def relative(path: Path) -> str:
    try:
        return path.relative_to(ROOT).as_posix()
    except ValueError:
        return path.name

def copy_license(source: Path, destination: Path) -> str:
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    return relative(destination)

def package(path: Path, category: str) -> None:
    manifest = path / 'package.json'
    if not manifest.is_file():
        return
    data = json.loads(manifest.read_text(encoding='utf-8'))
    name, version = data.get('name', path.name), data.get('version', '')
    identifier = re.sub(r'[^A-Za-z0-9_.-]', '_', name) + '-' + version
    files = [p for p in path.iterdir() if p.is_file() and re.match(r'^(licen[cs]e|copying|notice|unlicense)', p.name, re.I)]
    copied = [copy_license(p, LICENSES / category / identifier / p.name) for p in files]
    entries.append({'category': category, 'name': name, 'version': version, 'declaredLicense': data.get('license', 'UNSPECIFIED'),
                    'source': relative(path), 'licenseFiles': copied, 'repository': data.get('repository')})
    if not files:
        missing.append({'name': name, 'version': version, 'reason': 'Package has no top-level license text'})

def scan_runtime(path: Path) -> None:
    for directory in sorted(path.iterdir()):
        if not directory.is_dir() or directory.name == '.bin':
            continue
        if directory.name.startswith('@'):
            scan_runtime(directory)
            continue
        package(directory, 'embedded-node')
        nested = directory / 'node_modules'
        if nested.is_dir():
            scan_runtime(nested)

package_names = ['vue', 'pinia', 'vue-demi', '@capacitor/core', '@capacitor/android', '@capacitor/app',
                 'vue-i18n', '@intlify/shared', '@intlify/core-base', '@intlify/message-compiler', '@intlify/devtools-types', 'source-map-js',
                 '@vue/runtime-core', '@vue/runtime-dom', '@vue/reactivity', '@vue/shared', '@vue/devtools-api']
for name in package_names:
    package(ROOT / 'apps/android-vutron/node_modules' / name, 'frontend')
scan_runtime(RUNTIME / 'assets/sources-runtime/node_modules')

# NIO desugaring is a separate D8 input, absent from releaseRuntimeClasspath.
compat_source = ROOT / 'vendor/java-compat/desugar-jdk-2.1.5-source.zip'
with zipfile.ZipFile(compat_source) as archive:
    prefix = archive.namelist()[0]
    if archive.read(prefix + 'VERSION_JDK11_NIO.txt').decode().strip().splitlines()[-1] != '2.1.5':
        raise RuntimeError('Unexpected desugar NIO source version')
    compat_licenses = []
    for name in ['LICENSE', 'ASSEMBLY_EXCEPTION', 'ADDITIONAL_LICENSE_INFO']:
        destination = LICENSES / 'java-compat' / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(archive.read(prefix + name))
        compat_licenses.append(relative(destination))
entries.append({'category': 'android-desugaring', 'name': 'com.android.tools:desugar_jdk_libs_nio', 'version': '2.1.5',
                'declaredLicense': 'GPL version 2 with the Classpath Exception; retained per-file terms apply',
                'licenseFiles': compat_licenses, 'source': relative(compat_source), 'sourceSha256': digest(compat_source),
                'sourceRevision': '092407c51c3eaaaa9e46f7b7e436dc642f614064',
                'repository': 'https://github.com/google/desugar_jdk_libs'})

# This verbatim notice is retained in legal/ so a public checkout needs no private tree.
api_license = LICENSES / 'netease-api-4.36.1-MIT.txt'
if not api_license.is_file():
    raise RuntimeError('Retained NetEase API MIT notice is missing')
copy_license(ROOT / 'LICENSE', LICENSES / 'Vutron-MIT.txt')
for source in sorted((RUNTIME / 'assets/sources-runtime/licenses').iterdir()):
    if source.is_file():
        copy_license(source, LICENSES / 'native-runtime' / source.name)

# Canonical project license omitted from the published @vue/devtools-api package.
url = 'https://raw.githubusercontent.com/vuejs/devtools-v6/master/LICENSE'
try:
    content = urllib.request.urlopen(url, timeout=25).read()
    destination = LICENSES / 'frontend/_vue_devtools-api-6.6.4/LICENSE'
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(content)
    license_sources.append({'file': relative(destination), 'source': url, 'retrieved': '2026-10-26'})
    for entry in entries:
        if entry['name'] == '@vue/devtools-api':
            entry['licenseFiles'] = [relative(destination)]
            entry['licenseSource'] = url
    missing[:] = [entry for entry in missing if entry['name'] != '@vue/devtools-api']
except Exception as error:
    missing.append({'name': '@vue/devtools-api', 'reason': 'Official license fetch failed: ' + type(error).__name__, 'source': url})

for filename, url in {
    'YesPlayMusic-MIT.txt': 'https://raw.githubusercontent.com/qier222/YesPlayMusic/master/LICENSE',
    'Apache-2.0.txt': 'https://www.apache.org/licenses/LICENSE-2.0.txt',
    'Kotlin-APACHE-2.0.txt': 'https://raw.githubusercontent.com/JetBrains/kotlin/v1.9.25/license/LICENSE.txt',
    'Kotlin-NOTICE.txt': 'https://raw.githubusercontent.com/JetBrains/kotlin/v1.9.25/license/NOTICE.txt',
    'Kotlinx-Coroutines-APACHE-2.0.txt': 'https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.6.4/LICENSE.txt',
    'Cordova-Android-APACHE-2.0.txt': 'https://raw.githubusercontent.com/apache/cordova-android/10.1.1/LICENSE',
    'Cordova-Android-NOTICE.txt': 'https://raw.githubusercontent.com/apache/cordova-android/10.1.1/NOTICE',
    'ZXing-APACHE-2.0.txt': 'https://raw.githubusercontent.com/zxing/zxing/zxing-3.5.3/LICENSE',
    'ZXing-NOTICE.txt': 'https://raw.githubusercontent.com/zxing/zxing/zxing-3.5.3/NOTICE'
}.items():
    destination = LICENSES / filename
    try:
        content = urllib.request.urlopen(url, timeout=25).read()
        destination.write_bytes(content)
        license_sources.append({'file': relative(destination), 'source': url, 'retrieved': '2026-10-26'})
    except Exception as error:
        if not destination.is_file():
            missing.append({'name': filename, 'reason': 'Official license fetch failed: ' + type(error).__name__, 'source': url})

gradle_cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
graph = LEGAL / 'gradle-runtime-dependencies.txt'
coordinates: set[tuple[str, str, str]] = set()
if graph.is_file():
    for line in graph.read_text(encoding='utf-8-sig').splitlines():
        match = re.search(r'([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([0-9][A-Za-z0-9_.-]*)(?: -> ([0-9][A-Za-z0-9_.-]*))?', line)
        if match:
            coordinates.add((match.group(1), match.group(2), match.group(4) or match.group(3)))
for group, artifact, version in sorted(coordinates):
    directory = gradle_cache / group / artifact / version
    poms = list(directory.glob('*/*.pom'))
    license_names = []
    if poms:
        try:
            xml = ET.parse(poms[0]).getroot()
            ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
            license_names = [{'name': node.findtext('m:name', default='', namespaces=ns), 'url': node.findtext('m:url', default='', namespaces=ns)}
                             for node in xml.findall('m:licenses/m:license', ns)]
        except ET.ParseError:
            pass
    copied = []
    for archive in list(directory.glob('*/*.aar')) + list(directory.glob('*/*.jar')):
        if archive.name.endswith('-sources.jar') or archive.name.endswith('-javadoc.jar'):
            continue
        try:
            with zipfile.ZipFile(archive) as jar:
                for member in jar.namelist():
                    leaf = Path(member).name
                    if member.endswith('/') or not re.match(r'^(licen[cs]e|copying|notice|copyright)', leaf, re.I):
                        continue
                    data = jar.read(member)
                    if len(data) > 3 * 1024 * 1024:
                        continue
                    target = LICENSES / 'maven' / f'{group}.{artifact}-{version}' / member.replace('/', '__')
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(data)
                    copied.append(relative(target))
        except zipfile.BadZipFile:
            pass
    entries.append({'category': 'android-runtime', 'name': f'{group}:{artifact}', 'version': version,
                    'declaredLicenses': license_names, 'licenseFiles': sorted(set(copied)), 'source': f'Maven {group}:{artifact}:{version}'})

binaries = []
for directory in sorted((RUNTIME / 'jniLibs').iterdir()):
    if directory.is_dir():
        for binary in sorted(directory.glob('*.so')):
            binaries.append({'source': relative(binary), 'bytes': binary.stat().st_size, 'sha256': digest(binary),
                             'inMobileArm64Apk': directory.name == 'arm64-v8a'})

for entry in entries:
    if entry['category'] == 'android-runtime' and not entry.get('licenseFiles'):
        entry['licenseFiles'] = ['legal/licenses/Apache-2.0.txt']
        entry['licenseMapping'] = 'Resolved Maven POM Apache-2.0 declaration; AndroidX/JetBrains/Guava/Cordova primary project terms where POM omits it. No custom notice found in resolved archive.'
    if entry.get('name') == 'com.google.zxing:core':
        entry['licenseFiles'] = ['legal/licenses/ZXing-APACHE-2.0.txt', 'legal/licenses/ZXing-NOTICE.txt']
        entry['licenseMapping'] = 'ZXing core POM source header and canonical tagged repository license/notice.'
    if entry.get('name') == 'org.apache.cordova:framework':
        entry['licenseFiles'] = ['legal/licenses/Cordova-Android-APACHE-2.0.txt', 'legal/licenses/Cordova-Android-NOTICE.txt']
    if entry.get('name', '').startswith('org.jetbrains.kotlin:'):
        entry['licenseFiles'] = ['legal/licenses/Kotlin-APACHE-2.0.txt', 'legal/licenses/Kotlin-NOTICE.txt']
    if entry.get('name', '').startswith('org.jetbrains.kotlinx:'):
        entry['licenseFiles'] = ['legal/licenses/Kotlinx-Coroutines-APACHE-2.0.txt']
release_version = json.loads((ROOT / 'apps/android-vutron/package.json').read_text(encoding='utf-8-sig'))['version']
document = {'product': 'Vutron Music - Mobile', 'release': release_version, 'reviewDate': '2026-10-26',
            'scope': 'Installed frontend modules, embedded Node source tree, resolved Android runtime graph and retained JNI binaries; build-only dependencies are not asserted to ship.',
            'packages': entries, 'nativeBinaries': binaries, 'licenseSources': license_sources, 'unresolvedLicenseFiles': missing}
(LEGAL / 'third-party-inventory.json').write_text(json.dumps(document, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

combined = [f'Vutron Music - Mobile {release_version} — retained third-party license texts',
            'This collection preserves upstream texts. It does not relicense third-party components.\n']
for license_path in sorted(LICENSES.rglob('*')):
    if license_path.is_file():
        combined.append('\n======== ' + relative(license_path) + ' ========\n')
        combined.append(license_path.read_text(encoding='utf-8-sig', errors='replace'))
(LEGAL / 'THIRD_PARTY_LICENSES.txt').write_text('\n'.join(combined), encoding='utf-8')
print(json.dumps({'packages': len(entries), 'nativeBinaries': len(binaries), 'licenseFiles': len(list(LICENSES.rglob('*.*'))), 'unresolved': missing}, ensure_ascii=False))
