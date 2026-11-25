#!/usr/bin/env python3
"""Builds Lightshade Shader Loader and its two built-in packs offline. Never installs, starts or changes Minecraft.

Writes build/lightshade-shader-loader-<version>.jar, build/Lightshade-Daylight-<version>.zip and
build/Lightshade-Aurora-<version>.zip. Exit code 0 only when javac reports no errors and no warnings.

    python3 tools/build.py
"""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import sys
from zipfile import ZipFile, ZIP_DEFLATED

sys.path.insert(0, str(Path(__file__).resolve().parent))
import env  # noqa: E402
import icons  # noqa: E402
import packs  # noqa: E402

root = env.ROOT
api, sodium = env.require()
icons.main()
deps = env.BUILD / 'deps'
deps.mkdir(parents=True, exist_ok=True)
with ZipFile(api) as archive:
    for name in archive.namelist():
        if name.endswith('.jar'):
            target = deps / Path(name).name
            data = archive.read(name)
            if not target.exists() or target.read_bytes() != data:
                target.write_bytes(data)
classes = env.BUILD / 'classes'
shutil.rmtree(classes, ignore_errors=True)
classes.mkdir(parents=True)
libraries = [env.CLIENT, sodium, *sorted(deps.glob('*.jar')), *env.classpath_libraries()]
classpath = ':'.join(map(str, libraries))
sources = sorted((root / 'src/main/java').rglob('*.java'))
compiled = subprocess.run([str(env.JDK / 'bin/javac'), '--release', '25', '-proc:none', '-encoding', 'UTF-8',
                           '-Xlint:all,-serial,-processing,-classfile,-options', '-Werror',
                           '-classpath', classpath, '-d', str(classes), *map(str, sources)])
if compiled.returncode:
    sys.exit(compiled.returncode)
shutil.copytree(root / 'src/main/resources', classes, dirs_exist_ok=True)
packs.write_resources(classes)
shutil.copy2(root / 'LICENSE', classes / 'LICENSE')
artifact = env.BUILD / env.JAR_NAME
with ZipFile(artifact, 'w', compression=ZIP_DEFLATED) as archive:
    for path in sorted(classes.rglob('*')):
        if path.is_file():
            archive.writestr(packs.entry(path.relative_to(classes).as_posix()), path.read_bytes())
exported = packs.export(env.BUILD, env.VERSION)
evidence = {'artifact': artifact.name, 'packs': [p.name for p in exported],
            'sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in [artifact, *exported]},
            'java': env.JDK.name, 'minecraft': '26.3', 'fabric_api': api.name, 'sodium': sodium.name,
            'sources': len(sources)}
(env.BUILD / 'build.json').write_text(json.dumps(evidence, indent=2) + '\n')
print(json.dumps(evidence, indent=2))
