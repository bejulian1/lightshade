#!/usr/bin/env python3
"""Compiles every shader of both Lightshade packs to SPIR-V offline: defaults, each quality preset, all
lowest, all highest, and a set of mixed extremes, for every terrain variant Sodium builds (opaque,
cutout and the order-independent-transparency phases). Uses the shaderc library Minecraft 26.3 ships
(LWJGL natives); never starts the game. Warnings count as failures.

    python3 tools/check_shaders.py                      # both built-in packs
    python3 tools/check_shaders.py daylight             # one built-in pack
    python3 tools/check_shaders.py path/to/MyPack.zip   # your own pack: a ZIP or a folder with lightshade.json

Reads the same files the loader reads (lightshade.json, *.glsl, *.vsh, *.fsh) and resolves
#include <lightshade:...>, <minecraft:...> and <sodium:...> the way the loader does.
"""
from pathlib import Path
import ctypes as c
import json
import re
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import env  # noqa: E402
from packs import assemble, PACKS, MANIFEST  # noqa: E402

TERRAIN_VARIANTS = [{}, {'ALPHA_CUTOUT': 0.1}, {'OIT': 1, 'OIT_ACCUMULATE': 1},
                    {'OIT': 1, 'OIT_ALPHA_ONLY': 1, 'OIT_DEPTH_BOUNDS': 1},
                    {'OIT': 1, 'OIT_ALPHA_ONLY': 1, 'OIT_TRANSMITTANCE': 1}]
SODIUM_DEFINES = {'OIT_COEFF_COUNT': 8, 'OIT_COEFF_ATTACHMENT_COUNT': 2, 'OIT_WAVELET_RANK': 3}


def shaderc():
    target = env.BUILD / 'shaderc' / 'libshaderc.so'
    if not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(env.SHADERC) as jar:
            target.write_bytes(jar.read('linux/x64/org/lwjgl/shaderc/libshaderc.so'))
    lib = c.CDLL(str(target))

    def api(name, result, args):
        function = getattr(lib, name)
        function.restype = result
        function.argtypes = args
        return function
    p = c.c_void_p
    return {
        'compiler': api('shaderc_compiler_initialize', p, [])(),
        'options': api('shaderc_compile_options_initialize', p, [])(),
        'auto_bind': api('shaderc_compile_options_set_auto_bind_uniforms', None, [p, c.c_bool]),
        'compile': api('shaderc_compile_into_spv', p, [p, c.c_char_p, c.c_size_t, c.c_int, c.c_char_p, c.c_char_p, p]),
        'status': api('shaderc_result_get_compilation_status', c.c_int, [p]),
        'error': api('shaderc_result_get_error_message', c.c_char_p, [p]),
        'warnings': api('shaderc_result_get_num_warnings', c.c_size_t, [p]),
        'length': api('shaderc_result_get_length', c.c_size_t, [p]),
        'release': api('shaderc_result_release', None, [p]),
    }


def option_sets(manifest):
    # An option may be a bare number (a fixed define) or {"default": ..., "values": [...]}.
    options = {k: v if isinstance(v, dict) else {'default': v, 'values': [v]} for k, v in manifest.get('options', {}).items()}
    for value in options.values():
        value.setdefault('values', [value['default']])
    defaults = {k: v['default'] for k, v in options.items()}
    yield 'defaults', defaults
    for preset in manifest.get('presets', []):
        yield 'preset ' + preset['name'], defaults | preset['values']
    lowest = {k: min(v['values']) for k, v in options.items()}
    highest = {k: max(v['values']) for k, v in options.items()}
    yield 'all lowest', lowest
    yield 'all highest', highest
    # Mixed extremes: what a player can really combine. Shadows off with everything else high, and the
    # reverse; every quality step at its top with the looks at their bottom, and so on.
    yield 'highest, shadows off', highest | {'SHADOWS': 0.0}
    yield 'lowest, shadows on', lowest | {'SHADOWS': 1.0}
    levels = [k for k in options if k in ('WATER_REFLECTION_QUALITY', 'CLOUD_QUALITY', 'GODRAYS', 'VOLUMETRIC_LIGHT',
                                           'AMBIENT_OCCLUSION', 'SHADOW_CASCADES', 'CLOUD_SHADOWS', 'SHADOWS')]
    yield 'quality high, looks low', lowest | {k: highest[k] for k in levels}
    yield 'quality low, looks high', highest | {k: lowest[k] for k in levels}
    for key in options:
        values = options[key]['values']
        if len(values) > 2:
            for value in (min(values), max(values)):
                if value != defaults[key]:
                    yield f'{key}={value}', defaults | {key: value}


def define(value):
    return str(int(value)) if float(value) == int(value) else repr(float(value))


def load(name):
    """A built-in pack by name, or a pack folder or ZIP by path."""
    if name in PACKS:
        return name, assemble(name)
    path = Path(name).expanduser()
    keep = ('.glsl', '.vsh', '.fsh')
    if path.is_dir():
        files = {f.relative_to(path).as_posix(): f.read_text(encoding='utf-8') for f in sorted(path.rglob('*'))
                 if f.is_file() and not f.is_symlink() and (f.name == MANIFEST or f.suffix in keep)}
    elif path.is_file() and path.suffix.lower() == '.zip':
        with zipfile.ZipFile(path) as archive:
            files = {n: archive.read(n).decode('utf-8') for n in archive.namelist()
                     if not n.endswith('/') and (n == MANIFEST or n.endswith(keep))}
    else:
        raise SystemExit(f'{name}: not a built-in pack ({", ".join(PACKS)}), a pack folder or a ZIP')
    if MANIFEST not in files:
        raise SystemExit(f'{name}: no {MANIFEST} at the pack root')
    return path.name, files


def main(names):
    sc = shaderc()
    sc['auto_bind'](sc['options'], True)
    failures = compiled = 0
    report = {}
    with zipfile.ZipFile(env.CLIENT) as client, zipfile.ZipFile(env.SODIUM) as sodium:
        jars = {'minecraft': client, 'sodium': sodium}
        for name in names or PACKS:
            name, files = load(name)
            manifest = json.loads(files[MANIFEST])

            def expand(text, depth=0):
                if depth > 32:
                    raise ValueError('include depth')

                def include(match):
                    namespace, path = match[1].split(':', 1)
                    if namespace == 'lightshade':
                        body = files.get('shaders/include/' + path) or files.get('shaders/' + path)
                        if body is None:
                            raise KeyError('missing lightshade include ' + path)
                    else:
                        body = jars[namespace].read(f'assets/{namespace}/shaders/include/{path}').decode()
                    return expand(body, depth + 1)
                return re.sub(r'^\s*#include <([^>]+)>', include, text, flags=re.M)

            programs = [('terrain.vsh', 0, manifest['terrain']['vertex'], [{'USE_VERTEX_COMPRESSION': 1}])]
            programs += [('terrain.fsh', 1, manifest['terrain']['fragment'], TERRAIN_VARIANTS)]
            programs += [(p['name'], 1, p['fragment'], [{}]) for p in manifest['passes']]
            if 'shadow' in manifest:
                programs += [('shadow.vsh', 0, manifest['shadow']['vertex'], [{'USE_VERTEX_COMPRESSION': 1}])]
            sets = list(option_sets(manifest))
            seen = set()
            for label, values in sets:
                key = tuple(sorted(values.items()))
                if key in seen:
                    continue
                seen.add(key)
                for program, stage, path, variants in programs:
                    for extra in variants:
                        defines = values | SODIUM_DEFINES | extra
                        text = '\n'.join(f'#define {k} {define(v)}' for k, v in defines.items())
                        source = expand(files[path]).replace('#version 450', '#version 450\n' + text, 1).encode()
                        result = sc['compile'](sc['compiler'], source, len(source), stage, program.encode(), b'main', sc['options'])
                        ok = sc['status'](result) == 0 and sc['warnings'](result) == 0 and sc['length'](result) > 0
                        compiled += 1
                        if not ok:
                            failures += 1
                            print(f'FAIL {name} [{label}] {program} {extra}\n{(sc["error"](result) or b"").decode()}')
                        sc['release'](result)
            report[name] = {'option_sets': len(seen), 'programs': len(programs)}
            print(f'{name}: {len(seen)} option sets x {len(programs)} programs')
    print(f'{compiled} SPIR-V compilations, {failures} failures')
    (env.BUILD / 'spirv.json').write_text(json.dumps({'compilations': compiled, 'failures': failures, 'packs': report}, indent=2) + '\n')
    return 1 if failures else 0


if __name__ == '__main__':
    raise SystemExit(main(sys.argv[1:]))
