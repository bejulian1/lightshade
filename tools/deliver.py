#!/usr/bin/env python3
"""Copies the built release files to a delivery folder with SHA-256 sums, after tools/test.py has passed.

    python3 tools/deliver.py                 # to dist/ (or $LIGHTSHADE_DELIVERY)
    python3 tools/deliver.py --to <folder>   # to any other folder, for example a modpack's release folder
    python3 tools/deliver.py --skip-tests    # only when test.py has just passed on this very build

Delivered: lightshade-shader-loader-<version>.jar, Lightshade-Daylight-<version>.zip,
Lightshade-Aurora-<version>.zip and LIGHTSHADE-SHA256SUMS.txt. Nothing else in the folder is touched.
"""
from pathlib import Path
import argparse
import hashlib
import shutil
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import env  # noqa: E402
import packs  # noqa: E402

parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
parser.add_argument('--to', type=Path, default=env.DELIVERY, help=f'delivery folder (default: {env.DELIVERY})')
parser.add_argument('--skip-tests', action='store_true', help='do not run tools/test.py first')
args = parser.parse_args()

if not args.skip_tests and subprocess.run([sys.executable, str(env.ROOT / 'tools/test.py')], stdout=subprocess.DEVNULL).returncode:
    raise SystemExit('tools/test.py failed: nothing delivered')
files = [env.BUILD / env.JAR_NAME, *(env.BUILD / f'{stem}-{env.VERSION}.zip' for _, stem in packs.PACKS.values())]
for source in files:
    if not source.is_file():
        raise SystemExit(f'{source.name} is missing: run tools/build.py first')
target = args.to.expanduser().resolve()
target.mkdir(parents=True, exist_ok=True)
delivered = []
for source in files:
    shutil.copy2(source, target / source.name)
    delivered.append(target / source.name)
sums = target / 'LIGHTSHADE-SHA256SUMS.txt'
sums.write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in delivered))
for path in [*delivered, sums]:
    print(path)
