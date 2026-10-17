#!/usr/bin/env python3
"""Bundle the preview APK and current non-ignored source without local state or credentials."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apk', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
apk = args.apk.resolve()
output = args.output.resolve()
if output.exists():
    raise SystemExit('Choose a new output folder; existing packages are preserved.')
if not apk.is_file():
    raise SystemExit('APK does not exist.')

# A public package must never contain this computer's owner enrollment credentials.
secret_values = []
config = ROOT / '.local/owner-config.properties'
if config.exists():
    for line in config.read_text().splitlines():
        if line.startswith('enrollment_token='):
            secret_values.append(line.split('=', 1)[1].strip().encode())
service = ROOT / '.local/owner/service.json'
if service.exists():
    for key, value in json.loads(service.read_text()).items():
        if any(part in key for part in ['token', 'secret', 'private']) and isinstance(value, str):
            secret_values.append(value.encode())
secret_values = [value for value in secret_values if len(value) >= 24]

def check(data, name):
    if any(value in data for value in secret_values):
        raise SystemExit(f'Local credential found in {name}; use -PrelayPreview=true for the APK.')

with zipfile.ZipFile(apk) as archive:
    for name in archive.namelist():
        if name.endswith('.dex'):
            check(archive.read(name), 'APK')

files = subprocess.check_output(
    ['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=ROOT
).decode().split('\0')
source = []
for name in sorted(set(files) - {''}):
    path = ROOT / name
    if not path.is_file():
        continue
    if any(part in {'.git', '.local', '.gradle', 'build', '__pycache__'} for part in Path(name).parts):
        raise SystemExit(f'Unexpected local path in source list: {name}')
    check(path.read_bytes(), name)
    source.append((path, name))

output.mkdir(parents=True)
shutil.copy2(apk, output / '00-Calculator-preview.apk')
shutil.copy2(ROOT / 'rebuild/DISTRIBUTION.md', output / '01-START-HERE.txt')
shutil.copy2(ROOT / 'rebuild/VERIFICATION.md', output / '02-TEST-RESULTS.txt')
with zipfile.ZipFile(output / '03-Calculator-and-Control-Panel-source.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for path, name in source:
        archive.write(path, f'Calculator-source/{name}')
checksums = []
for path in sorted(output.iterdir()):
    checksums.append(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}')
(output / '04-SHA256SUMS.txt').write_text('\n'.join(checksums) + '\n')
bundle = Path(str(output) + '.zip')
if bundle.exists():
    raise SystemExit('Folder created, but bundle archive already exists; it was preserved.')
with zipfile.ZipFile(bundle, 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(output.iterdir()):
        archive.write(path, f'{output.name}/{path.name}')
print(f'Package: {output}\nDownload archive: {bundle}\nSource files: {len(source)}')
