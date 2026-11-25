#!/usr/bin/env python3
"""The two built-in Lightshade packs, assembled from packs/common plus each pack's own files.

A pack's own file wins over the shared one with the same path. build.py puts the result in the loader
JAR (assets/lightshade/packs/<id>/ with an index.txt of its sources, plus pack.png), and export() writes the
same files as standalone ZIPs for shaderpacks/.
"""
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'packs'
# folder name -> (pack id inside the loader, ZIP name stem)
PACKS = {'daylight': ('daylight', 'Lightshade-Daylight'), 'aurora': ('aurora', 'Lightshade-Aurora')}
SUFFIXES = ('.json', '.glsl', '.vsh', '.fsh')
MANIFEST = 'lightshade.json'
STAMP = (2026, 11, 25, 0, 0, 0)


def assemble(name):
    files = {}
    for base in (SOURCE / 'common', SOURCE / name):
        for path in sorted(base.rglob('*')):
            if path.is_file() and path.suffix in SUFFIXES:
                files[path.relative_to(base).as_posix()] = path.read_text(encoding='utf-8')
    if MANIFEST not in files:
        raise SystemExit(f'packs/{name} has no {MANIFEST}')
    return files


def icon(name):
    path = SOURCE / name / 'pack.png'
    return path.read_bytes() if path.is_file() else None


def write_resources(classes):
    """Copy both packs into the JAR's class tree, each with an index of its source files."""
    for name, (pack_id, _) in PACKS.items():
        files = assemble(name)
        target = classes / 'assets/lightshade/packs' / pack_id
        for path, text in files.items():
            out = target / path
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(text, encoding='utf-8')
        (target / 'index.txt').write_text('\n'.join(sorted(files)) + '\n', encoding='utf-8')
        if icon(name):
            (target / 'pack.png').write_bytes(icon(name))


def entry(path):
    info = ZipInfo(path, STAMP)
    info.compress_type = ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    return info


def export(directory, version):
    """Standalone ZIPs with lightshade.json and pack.png at the root, for shaderpacks/."""
    written = []
    for name, (_, stem) in PACKS.items():
        files = assemble(name)
        archive = directory / f'{stem}-{version}.zip'
        with ZipFile(archive, 'w', compression=ZIP_DEFLATED) as output:
            for path, text in sorted(files.items()):
                output.writestr(entry(path), text.encode('utf-8'))
            if icon(name):
                output.writestr(entry('pack.png'), icon(name))
        written.append(archive)
    return written
