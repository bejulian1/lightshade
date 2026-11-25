"""Paths the Lightshade tools share. Every dependency is read from a local cache; nothing is downloaded
or installed, and the game is never started.

Environment variables (all optional when the defaults below exist on your machine):

    JAVA_HOME                A JDK 25 (javac + java).
    LIGHTSHADE_PRISM_CACHE   A Prism Launcher data folder that has run Minecraft 26.3 with Fabric Loader 0.19.5
                             (it holds the client JAR, the library JARs and their metadata).
    LIGHTSHADE_MODS          A folder with fabric-api-0.164.0+26.3.jar and sodium-fabric-0.9.3-beta.1+mc26.3.jar.
    LIGHTSHADE_DELIVERY      Where tools/deliver.py copies the release files (default: dist/).
"""
from functools import lru_cache
from pathlib import Path
import os

ROOT = Path(__file__).resolve().parents[1]
VERSION = '0.0.1'
MOD_ID = 'lightshade'
JAR_NAME = f'lightshade-shader-loader-{VERSION}.jar'

_HOME = Path.home()
_PRISM_CANDIDATES = [
    _HOME / '.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher',  # Flatpak
    _HOME / '.local/share/PrismLauncher',                                   # native Linux
    _HOME / 'Library/Application Support/PrismLauncher',                    # macOS
    Path(os.environ.get('APPDATA', '/nonexistent')) / 'PrismLauncher',      # Windows
]


def _prism_cache():
    if os.environ.get('LIGHTSHADE_PRISM_CACHE'):
        return Path(os.environ['LIGHTSHADE_PRISM_CACHE']).expanduser()
    for candidate in _PRISM_CANDIDATES:
        if (candidate / 'libraries/com/mojang/minecraft/26.3').is_dir():
            return candidate
    return _PRISM_CANDIDATES[0]


def _jdk():
    if os.environ.get('JAVA_HOME'):
        return Path(os.environ['JAVA_HOME']).expanduser()
    for root in (_HOME / '.local/share/jdks', Path('/usr/lib/jvm')):
        if root.is_dir():
            for jdk in sorted(root.glob('*25*'), reverse=True):
                if (jdk / 'bin/javac').is_file():
                    return jdk
    return _HOME / '.local/share/jdks/jdk-25'


CACHE = _prism_cache()
JDK = _jdk()
CLIENT = CACHE / 'libraries/com/mojang/minecraft/26.3/minecraft-26.3-client.jar'
SHADERC = CACHE / 'libraries/org/lwjgl/lwjgl-shaderc-natives-linux/3.4.3/lwjgl-shaderc-natives-linux-3.4.3.jar'
BUILD = ROOT / 'build'
DELIVERY = Path(os.environ['LIGHTSHADE_DELIVERY']).expanduser() if os.environ.get('LIGHTSHADE_DELIVERY') else ROOT / 'dist'

API_JAR = 'fabric-api-0.164.0+26.3.jar'
SODIUM_JAR = 'sodium-fabric-0.9.3-beta.1+mc26.3.jar'


def _mod_folders():
    """Where the exact Fabric API and Sodium builds may already sit, most explicit first."""
    if os.environ.get('LIGHTSHADE_MODS'):
        yield Path(os.environ['LIGHTSHADE_MODS']).expanduser()
        return
    yield ROOT / 'libs'
    for pattern in ('instances/*/minecraft/mods', 'instances/*/.minecraft/mods'):
        yield from sorted(CACHE.glob(pattern))
    # Last resort: a sibling project next to this repository that keeps a copy (at most four levels deep).
    for depth in range(1, 5):
        seen = set()
        for jar in sorted(ROOT.parent.glob('/'.join(['*'] * depth) + '/' + API_JAR)):
            if ROOT not in jar.parents and jar.parent not in seen:
                seen.add(jar.parent)
                yield jar.parent
        for jar in sorted(ROOT.parent.glob('/'.join(['*'] * depth) + '/' + SODIUM_JAR)):
            if ROOT not in jar.parents and jar.parent not in seen:
                seen.add(jar.parent)
                yield jar.parent


@lru_cache(maxsize=None)
def _find(name):
    folders = list(_mod_folders())
    for folder in folders:
        if (folder / name).is_file():
            return folder / name
    explicit = os.environ.get('LIGHTSHADE_MODS')
    raise SystemExit(
        f'{name} not found{" in LIGHTSHADE_MODS=" + explicit if explicit else ""}.\n'
        f'Lightshade builds offline against exactly {API_JAR} and {SODIUM_JAR}.\n'
        'Put both JARs in ./libs/ (ignored by git), or point LIGHTSHADE_MODS at a folder that holds them,\n'
        'for example the mods/ folder of a Prism instance that runs Minecraft 26.3:\n'
        '    export LIGHTSHADE_MODS="$HOME/.local/share/PrismLauncher/instances/<instance>/minecraft/mods"')


def __getattr__(name):
    """API, SODIUM and MODS are found on first use, so tools that only draw images never need them."""
    if name == 'API':
        return _find(API_JAR)
    if name == 'SODIUM':
        return _find(SODIUM_JAR)
    if name == 'MODS':
        return _find(API_JAR).parent
    raise AttributeError(name)


def require():
    """Fails with an actionable message when a cached dependency is missing."""
    missing = [p for p in (CLIENT, SHADERC, JDK / 'bin/javac', JDK / 'bin/java') if not p.is_file()]
    if missing:
        raise SystemExit('Missing cached dependencies:\n  ' + '\n  '.join(map(str, missing)) +
                         '\nSet JAVA_HOME to a JDK 25 and LIGHTSHADE_PRISM_CACHE to a Prism Launcher folder that has '
                         'launched Minecraft 26.3 with Fabric Loader 0.19.5. See CONTRIBUTING.md.')
    return _find(API_JAR), _find(SODIUM_JAR)


def classpath_libraries():
    """The libraries Minecraft 26.3, LWJGL 3.4.3 and Fabric Loader 0.19.5 use, from Prism's own metadata,
    so nothing from other cached game versions ends up on the compile classpath."""
    import json
    found = []
    for meta in ('net.minecraft/26.3.json', 'org.lwjgl3/3.4.3.json', 'net.fabricmc.fabric-loader/0.19.5.json',
                 'net.fabricmc.intermediary/26.3.json'):
        path = CACHE / 'meta' / meta
        if not path.is_file():
            continue
        data = json.loads(path.read_text())
        entries = list(data.get('libraries', []))
        if 'mainJar' in data:
            entries.append(data['mainJar'])
        for library in entries:
            parts = library['name'].split(':')
            if len(parts) != 3:
                continue  # natives and other classifiers
            group, artifact, version = parts
            jar = CACHE / 'libraries' / group.replace('.', '/') / artifact / version / f'{artifact}-{version}.jar'
            if jar.is_file() and jar not in found:
                found.append(jar)
    return found
