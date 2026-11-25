#!/usr/bin/env python3
"""Offline checks after tools/build.py. Never starts Minecraft.

1. Every shader of both packs compiles to SPIR-V for defaults, all four presets, all lowest, all highest
   and mixed extremes, across every terrain variant Sodium builds (check_shaders.py).
2. The Java unit tests: config, presets, pack loading, settings draft, pack screen state, option text,
   conflict guard (src/test/java, compiled with -Werror).
3. Translations: en_us, nb_no and no_no have the same keys, every key the code asks for exists, and every
   option of both packs has a name and a description.
4. The built JAR and pack ZIPs hold what the mod needs (mixins, entrypoints, icons, both packs, metadata).
5. Repository: README images exist, community files and the changelog entry are present.
6. Naming: none of the banned names (earlier working names, other products, any modpack) anywhere
   in the sources, docs, file names or the built files.
Exit code 0 only when all of it passes.
"""
from pathlib import Path
import json
import re
import shutil
import subprocess
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import env  # noqa: E402
import packs  # noqa: E402

ROOT = env.ROOT
problems = []


def step(title):
    print(f'\n== {title}', flush=True)


def fail(message):
    problems.append(message)
    print('FAIL', message)


jar_path = env.BUILD / env.JAR_NAME
zips = [env.BUILD / f'{stem}-{env.VERSION}.zip' for _, stem in packs.PACKS.values()]
for artifact in [jar_path, *zips]:
    if not artifact.is_file():
        raise SystemExit(f'{artifact.name} is missing: run tools/build.py first')

step('SPIR-V: both packs, every preset and extreme option set')
if subprocess.run([sys.executable, str(ROOT / 'tools/check_shaders.py')]).returncode:
    fail('shader compilation')

step('Java unit tests')
api, sodium = env.require()
libraries = [env.CLIENT, sodium, *sorted((env.BUILD / 'deps').glob('*.jar')), *env.classpath_libraries()]
classpath = ':'.join(map(str, [env.BUILD / 'classes', *libraries]))
out = env.BUILD / 'tests'
shutil.rmtree(out, ignore_errors=True)
out.mkdir(parents=True)
tests = sorted((ROOT / 'src/test/java').rglob('*.java'))
compiled = subprocess.run([str(env.JDK / 'bin/javac'), '--release', '25', '-proc:none', '-encoding', 'UTF-8',
                           '-Xlint:all,-serial,-processing,-classfile,-options', '-Werror', '-cp', classpath,
                           '-d', str(out), *map(str, tests)])
if compiled.returncode:
    fail('test compilation')
elif subprocess.run([str(env.JDK / 'bin/java'), '-cp', f'{out}:{classpath}', 'no.julian.lightshade.LightshadeTests']).returncode:
    fail('Java unit tests')

step('Translations')
lang_dir = ROOT / 'src/main/resources/assets/lightshade/lang'
langs = {name: json.loads((lang_dir / f'{name}.json').read_text(encoding='utf-8')) for name in ('en_us', 'nb_no', 'no_no')}
keys = {name: set(table) for name, table in langs.items()}
if not keys['en_us'] == keys['nb_no'] == keys['no_no']:
    fail(f'language files differ: {sorted(keys["en_us"] ^ keys["nb_no"])} {sorted(keys["en_us"] ^ keys["no_no"])}')
for name, table in langs.items():
    empty = [k for k, v in table.items() if not v.strip()]
    if empty:
        fail(f'{name} has empty values: {empty}')
used = set()
for source in (ROOT / 'src/main/java').rglob('*.java'):
    used |= set(re.findall(r'"((?:key\.)?lightshade\.[a-z_]+\.[a-z_]+)"', source.read_text(encoding='utf-8')))
missing = sorted(k for k in used if k not in langs['en_us'])
if missing:
    fail(f'keys used in code but not translated: {missing}')
option_keys = 0
for name in packs.PACKS:
    manifest = json.loads(packs.assemble(name)[packs.MANIFEST])
    for option in manifest['options']:
        option_keys += 1
        for suffix in ('', '.desc'):
            if f'lightshade.option.{option}{suffix}' not in langs['en_us']:
                fail(f'{name}: option {option}{suffix} has no translation')
    for preset in manifest['presets']:
        if f'lightshade.preset.{preset["name"].lower()}' not in langs['en_us']:
            fail(f'{name}: preset {preset["name"]} has no translation')
    for suffix in ('', '.desc', '.tag'):
        if f'lightshade.pack.{packs.PACKS[name][0]}{suffix}' not in langs['en_us']:
            fail(f'{name}: pack text {suffix or "name"} missing')
print(f'{len(keys["en_us"])} keys in each of en_us, nb_no, no_no; {len(used)} used by code; {option_keys} pack options covered')

step('Built files')
with zipfile.ZipFile(jar_path) as jar:
    names = set(jar.namelist())
    mod = json.loads(jar.read('fabric.mod.json'))
    mixins = json.loads(jar.read('lightshade.mixins.json'))
    if mod['id'] != 'lightshade' or mod['version'] != env.VERSION or mod['name'] != 'Lightshade Shader Loader':
        fail('fabric.mod.json identity')
    if mod.get('authors') != ['Julian'] or mod.get('license') != 'MIT':
        fail('fabric.mod.json authors/license')
    for field in ('homepage', 'sources', 'issues'):
        if not mod.get('contact', {}).get(field, '').startswith('https://github.com/bejulian1/lightshade'):
            fail(f'fabric.mod.json contact.{field}')
    for entry in [*mod['entrypoints']['client'], *mod['entrypoints']['sodium:config_api_user']]:
        if entry.replace('.', '/') + '.class' not in names:
            fail(f'entrypoint {entry} missing from the JAR')
    for mixin in mixins['client']:
        if f'{mixins["package"].replace(".", "/")}/{mixin}.class' not in names:
            fail(f'mixin {mixin} missing from the JAR')
    required = ['assets/lightshade/icon.png', 'assets/lightshade/textures/gui/default_pack.png', 'assets/lightshade/textures/gui/config-icon.png',
                'LICENSE', *[f'assets/lightshade/lang/{n}.json' for n in langs]]
    for pack_id, _ in packs.PACKS.values():
        required += [f'assets/lightshade/packs/{pack_id}/{f}' for f in ('lightshade.json', 'index.txt', 'pack.png', 'shaders/terrain.fsh')]
    for name in required:
        if name not in names:
            fail(f'{name} missing from the JAR')
    if any(n.startswith('assets/lightshade/packs/') and n.endswith('.json') and not n.endswith('lightshade.json') for n in names):
        fail('stray JSON in a built-in pack')
for archive in zips:
    with zipfile.ZipFile(archive) as z:
        listed = set(z.namelist())
        for name in ('lightshade.json', 'pack.png', 'shaders/terrain.fsh', 'shaders/terrain.vsh', 'shaders/display.fsh'):
            if name not in listed:
                fail(f'{archive.name} lacks {name}')
        if z.read('pack.png')[:8] != b'\x89PNG\r\n\x1a\n':
            fail(f'{archive.name} pack.png is not a PNG')
print(f'{jar_path.name}: {len(names)} entries; {", ".join(z.name for z in zips)} checked')

step('Repository')
readme = (ROOT / 'README.md').read_text(encoding='utf-8') if (ROOT / 'README.md').is_file() else ''
for image in re.findall(r'(docs/images/[A-Za-z0-9_.-]+\.png)', readme):
    if not (ROOT / image).is_file():
        fail(f'README.md shows {image}, which does not exist')
for name in ('LICENSE', 'README.md', 'CHANGELOG.md', 'CONTRIBUTING.md', 'CODE_OF_CONDUCT.md', 'SECURITY.md',
             'docs/PACK_FORMAT.md', 'docs/ARCHITECTURE.md'):
    if not (ROOT / name).is_file():
        fail(f'{name} is missing')
if (ROOT / 'LICENSE').is_file() and 'MIT License' not in (ROOT / 'LICENSE').read_text(encoding='utf-8'):
    fail('LICENSE is not the MIT License')
if (ROOT / 'CHANGELOG.md').is_file() and f'[{env.VERSION}]' not in (ROOT / 'CHANGELOG.md').read_text(encoding='utf-8'):
    fail(f'CHANGELOG.md has no entry for {env.VERSION}')
print('README images and community files checked')

step('Naming')
# Names this project must never carry: earlier working names, unrelated products and any one modpack.
# Spelled in pieces so this file does not match itself. Plain words match anywhere, in any case; the
# three-letter one matches only as a whole word or a camel-case/snake-case identifier part, so that
# unrelated library method names that merely contain those letters do not count.
WORDS = ['lys' + 'vev', 'dag' + 'lys', 'nord' + 'lys', 'ast' + 'ra', 'cod' + 'ex', 'all' + 'verk']
SHORT = 'a' + 'sl'
banned = re.compile('|'.join([*(f'(?i:{w})' for w in WORDS),
                              rf'(?<![A-Za-z])(?i:{SHORT})(?![a-z])',
                              rf'(?<=[a-z0-9])(?:{SHORT.capitalize()}|{SHORT.upper()})(?![a-z])']))
checked = 0
SKIP_DIRS = {'build', 'dist', 'libs', '.git', '__pycache__'}


def scan(label, text):
    global checked
    checked += 1
    for number, line in enumerate(text.splitlines(), 1):
        if banned.search(line):
            fail(f'{label}:{number}: {line.strip()[:120]}')


for path in sorted(ROOT.rglob('*')):
    rel_parts = path.relative_to(ROOT).parts
    if SKIP_DIRS & set(rel_parts):
        continue
    rel = path.relative_to(ROOT).as_posix()
    if banned.search(rel):
        fail(f'file name {rel}')
    if not path.is_file() or path.suffix in ('.png', '.pyc', '.class', '.jar', '.zip'):
        continue
    scan(rel, path.read_text(encoding='utf-8', errors='replace'))
for archive in [jar_path, *zips]:
    with zipfile.ZipFile(archive) as z:
        for name in z.namelist():
            if banned.search(name):
                fail(f'{archive.name}: entry name {name}')
            data = z.read(name)
            if name.endswith('.class'):
                # Constant-pool strings: class names, method names, literals.
                scan(f'{archive.name}!{name}', data.decode('latin-1'))
            elif not name.endswith('.png'):
                scan(f'{archive.name}!{name}', data.decode('utf-8', errors='replace'))
print(f'{checked} files and archive entries scanned')

print()
if problems:
    print(f'{len(problems)} problem(s)')
    raise SystemExit(1)
print('all tests passed')
